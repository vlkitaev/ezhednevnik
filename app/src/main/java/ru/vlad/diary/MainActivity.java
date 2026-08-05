package ru.vlad.diary;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final String PREFS = "diary";
    private static final Locale RU = new Locale("ru", "RU");

    private final SimpleDateFormat keyFmt = new SimpleDateFormat("yyyy-MM-dd", RU);
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("d MMMM", RU);
    private final SimpleDateFormat dowFmt = new SimpleDateFormat("EEEE, yyyy", RU);
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", RU);
    private final SimpleDateFormat fullFmt = new SimpleDateFormat("d MMMM, HH:mm", RU);

    private final Calendar day = Calendar.getInstance();
    private final List<Task> tasks = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();

    private SharedPreferences prefs;
    private RowAdapter adapter;
    private static final int REQ_VOICE = 101;
    private String lastToday = "";
    private TextView tvDate, tvDow, tvProgress, tvEmpty, btnToday;
    private ProgressBar pb;
    private EditText etInput;
    private ListView list;

    /** Пункт дня. Может содержать подпункты. */
    static class Task {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String text;
        boolean done;
        long remind;
        boolean expanded;
        String from;
        List<Sub> subs = new ArrayList<>();
    }

    /** Подпункт. */
    static class Sub {
        String text;
        boolean done;
    }

    /** Строка списка: sub == -1 значит сам пункт, иначе индекс подпункта. */
    static class Row {
        int task;
        int sub;
        Row(int task, int sub) {
            this.task = task;
            this.sub = sub;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        tvDate = findViewById(R.id.tvDate);
        tvDow = findViewById(R.id.tvDow);
        tvProgress = findViewById(R.id.tvProgress);
        tvEmpty = findViewById(R.id.tvEmpty);
        btnToday = findViewById(R.id.btnToday);
        pb = findViewById(R.id.pb);
        etInput = findViewById(R.id.etInput);
        list = findViewById(R.id.list);

        adapter = new RowAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.btnPrev).setOnClickListener(v -> shiftDay(-1));
        findViewById(R.id.btnNext).setOnClickListener(v -> shiftDay(1));
        findViewById(R.id.btnAdd).setOnClickListener(v -> addTask());
        tvDate.setOnClickListener(v -> pickDate());
        tvDow.setOnClickListener(v -> pickDate());
        btnToday.setOnClickListener(v -> {
            day.setTimeInMillis(System.currentTimeMillis());
            load();
        });
        ((ImageButton) findViewById(R.id.btnVoice)).setOnClickListener(v -> startVoice());

        Reminders.ensureChannel(this);
        handleIntent(getIntent());

        lastToday = todayKey();
        int moved = autoCarry();
        load();
        if (moved > 0) {
            Toast.makeText(this, "Перенесено с прошлых дней: " + moved, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        load();
    }

    /** Открытие по нажатию на уведомление — сразу на нужную дату. */
    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String key = intent.getStringExtra(Reminders.EXTRA_DAY);
        if (key != null && key.length() == 10) {
            try {
                day.setTime(keyFmt.parse(key));
            } catch (Exception ignored) {
                // некорректная дата в уведомлении — остаёмся на сегодня
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!lastToday.equals(todayKey())) {
            lastToday = todayKey();
            day.setTimeInMillis(System.currentTimeMillis());
            int moved = autoCarry();
            load();
            if (moved > 0) {
                Toast.makeText(this, "Перенесено с прошлых дней: " + moved, Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ---------- дата ----------

    private String dayKey() {
        return keyFmt.format(day.getTime());
    }

    private String todayKey() {
        return keyFmt.format(Calendar.getInstance().getTime());
    }

    private void shiftDay(int delta) {
        day.add(Calendar.DAY_OF_MONTH, delta);
        load();
    }

    private void pickDate() {
        new DatePickerDialog(this, (view, y, m, d) -> {
            day.set(y, m, d);
            load();
        }, day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ---------- хранение ----------

    private List<Task> parse(String raw) {
        List<Task> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Task t = new Task();
                String uid = o.optString("i", "");
                if (!uid.isEmpty()) {
                    t.uid = uid;
                }
                t.remind = o.optLong("r", 0);
                t.text = o.optString("t");
                t.done = o.optBoolean("d", false);
                t.expanded = o.optBoolean("e", false);
                t.from = o.has("f") ? o.optString("f", null) : null;
                JSONArray subs = o.optJSONArray("s");
                if (subs != null) {
                    for (int j = 0; j < subs.length(); j++) {
                        JSONObject so = subs.getJSONObject(j);
                        Sub s = new Sub();
                        s.text = so.optString("t");
                        s.done = so.optBoolean("d", false);
                        t.subs.add(s);
                    }
                }
                out.add(t);
            }
        } catch (Exception e) {
            // повреждённые данные за день игнорируем
        }
        return out;
    }

    private String serialize(List<Task> src) {
        JSONArray arr = new JSONArray();
        try {
            for (Task t : src) {
                JSONObject o = new JSONObject();
                o.put("i", t.uid);
                o.put("t", t.text);
                o.put("d", t.done);
                if (t.remind > 0) {
                    o.put("r", t.remind);
                }
                o.put("e", t.expanded);
                if (t.from != null) {
                    o.put("f", t.from);
                }
                if (!t.subs.isEmpty()) {
                    JSONArray subs = new JSONArray();
                    for (Sub s : t.subs) {
                        JSONObject so = new JSONObject();
                        so.put("t", s.text);
                        so.put("d", s.done);
                        subs.put(so);
                    }
                    o.put("s", subs);
                }
                arr.put(o);
            }
        } catch (Exception e) {
            return "[]";
        }
        return arr.toString();
    }

    private void writeDay(String key, List<Task> src) {
        if (src.isEmpty()) {
            prefs.edit().remove(key).apply();
        } else {
            prefs.edit().putString(key, serialize(src)).apply();
        }
    }

    private void load() {
        tasks.clear();
        tasks.addAll(parse(prefs.getString(dayKey(), "[]")));
        refresh();
    }

    private void save() {
        writeDay(dayKey(), tasks);
    }

    // ---------- автоматический перенос ----------

    /**
     * Переносит незакрытые пункты всех прошедших дней на сегодня.
     * Выполненные остаются на своих датах. Возвращает число перенесённых.
     */
    private int autoCarry() {
        String target = todayKey();
        List<String> past = new ArrayList<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String key = e.getKey();
            if (key.length() == 10 && key.compareTo(target) < 0 && e.getValue() instanceof String) {
                past.add(key);
            }
        }
        if (past.isEmpty()) {
            return 0;
        }
        java.util.Collections.sort(past);

        List<Task> todayList = parse(prefs.getString(target, "[]"));
        int moved = 0;
        for (String key : past) {
            List<Task> src = parse(prefs.getString(key, "[]"));
            List<Task> keep = new ArrayList<>();
            boolean changed = false;
            for (Task t : src) {
                if (isDone(t)) {
                    keep.add(t);
                } else {
                    if (t.from == null) {
                        t.from = key;
                    }
                    if (t.remind > 0 && t.remind < System.currentTimeMillis()) {
                        t.remind = 0;
                    }
                    todayList.add(t);
                    moved++;
                    changed = true;
                }
            }
            if (changed) {
                writeDay(key, keep);
            }
        }
        if (moved > 0) {
            writeDay(target, todayList);
        }
        return moved;
    }

    /** "2026-08-03" -> "перенесено с 3 августа" */
    private String fromLabel(String key) {
        try {
            return "перенесено с " + dateFmt.format(keyFmt.parse(key));
        } catch (Exception e) {
            return "перенесено";
        }
    }

    // ---------- голосовой ввод ----------

    private void startVoice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите");
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Голосовой ввод недоступен на этом устройстве",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VOICE || resultCode != RESULT_OK || data == null) {
            return;
        }
        List<String> res = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (res == null || res.isEmpty()) {
            return;
        }
        String text = res.get(0).trim();
        if (text.isEmpty()) {
            return;
        }
        text = text.substring(0, 1).toUpperCase(RU) + text.substring(1);
        String current = etInput.getText().toString().trim();
        etInput.setText(current.isEmpty() ? text : current + " " + text);
        etInput.setSelection(etInput.getText().length());
    }

    // ---------- действия ----------

    private boolean isDone(Task t) {
        if (t.subs.isEmpty()) {
            return t.done;
        }
        for (Sub s : t.subs) {
            if (!s.done) {
                return false;
            }
        }
        return true;
    }

    private void addTask() {
        String text = etInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            return;
        }
        Task t = new Task();
        t.text = text;
        t.done = false;
        tasks.add(t);
        etInput.setText("");
        save();
        refresh();
        list.smoothScrollToPosition(rows.size() - 1);
    }

    private void askText(String title, String initial, final OnText cb) {
        final EditText field = new EditText(this);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setText(initial);
        field.setSelection(field.getText().length());
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(field, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Готово", (d, w) -> {
                    String text = field.getText().toString().trim();
                    if (!TextUtils.isEmpty(text)) {
                        cb.onText(text);
                    }
                })
                .show();
    }

    interface OnText {
        void onText(String text);
    }

    private void taskMenu(final int index) {
        final Task t = tasks.get(index);
        final List<String> items = new ArrayList<>();
        items.add("Изменить текст");
        items.add("Добавить подпункт");
        items.add(t.remind > 0 ? "Изменить напоминание" : "Напомнить\u2026");
        if (t.remind > 0) {
            items.add("Убрать напоминание");
        }
        items.add("Удалить");

        new AlertDialog.Builder(this)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String choice = items.get(which);
                    if ("Изменить текст".equals(choice)) {
                        askText("Изменить", t.text, text -> {
                            t.text = text;
                            save();
                            if (t.remind > System.currentTimeMillis()) {
                                Reminders.schedule(MainActivity.this, t.uid, t.text, dayKey(), t.remind);
                            }
                            refresh();
                        });
                    } else if ("Добавить подпункт".equals(choice)) {
                        askText("Новый подпункт", "", text -> {
                            Sub s2 = new Sub();
                            s2.text = text;
                            s2.done = false;
                            t.subs.add(s2);
                            t.expanded = true;
                            save();
                            refresh();
                        });
                    } else if ("Убрать напоминание".equals(choice)) {
                        clearReminder(index);
                    } else if ("Удалить".equals(choice)) {
                        new AlertDialog.Builder(MainActivity.this)
                                .setMessage("Удалить «" + t.text + "»?")
                                .setNegativeButton("Отмена", null)
                                .setPositiveButton("Удалить", (dd, ww) -> {
                                    Reminders.cancel(MainActivity.this, t.uid);
                                    tasks.remove(index);
                                    save();
                                    refresh();
                                })
                                .show();
                    } else {
                        pickReminder(index);
                    }
                })
                .show();
    }

    // ---------- напоминания ----------

    /** Выбор даты и времени. Если дата другая — пункт переезжает на неё. */
    private void pickReminder(final int index) {
        final Task t = tasks.get(index);
        final Calendar init = Calendar.getInstance();
        if (t.remind > 0) {
            init.setTimeInMillis(t.remind);
        } else {
            init.setTime(day.getTime());
            init.set(Calendar.HOUR_OF_DAY, 9);
            init.set(Calendar.MINUTE, 0);
        }
        // стартовая дата не должна быть раньше сегодняшней, иначе DatePicker ругается
        if (init.getTimeInMillis() < System.currentTimeMillis()) {
            Calendar now = Calendar.getInstance();
            init.set(now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH));
        }

        DatePickerDialog dp = new DatePickerDialog(this, (view, y, m, d) ->
                new TimePickerDialog(MainActivity.this, (tv, hour, minute) -> {
                    Calendar at = Calendar.getInstance();
                    at.set(y, m, d, hour, minute, 0);
                    at.set(Calendar.MILLISECOND, 0);
                    applyReminder(index, at);
                }, init.get(Calendar.HOUR_OF_DAY), init.get(Calendar.MINUTE), true).show(),
                init.get(Calendar.YEAR), init.get(Calendar.MONTH), init.get(Calendar.DAY_OF_MONTH));
        dp.getDatePicker().setMinDate(System.currentTimeMillis() - 60000);
        dp.setTitle("Когда напомнить");
        dp.show();
    }

    private void applyReminder(int index, Calendar at) {
        if (index < 0 || index >= tasks.size()) {
            return;
        }
        if (at.getTimeInMillis() <= System.currentTimeMillis()) {
            Toast.makeText(this, "Это время уже прошло", Toast.LENGTH_SHORT).show();
            return;
        }
        Task t = tasks.get(index);
        String targetKey = keyFmt.format(at.getTime());
        t.remind = at.getTimeInMillis();
        t.done = false;

        if (targetKey.equals(dayKey())) {
            save();
        } else {
            tasks.remove(index);
            save();
            List<Task> dst = parse(prefs.getString(targetKey, "[]"));
            dst.add(t);
            writeDay(targetKey, dst);
        }

        boolean exact = Reminders.schedule(this, t.uid, t.text, targetKey, t.remind);
        askNotificationPermission();
        refresh();
        Toast.makeText(this, "Напоминание: " + fullFmt.format(at.getTime()),
                Toast.LENGTH_LONG).show();
        if (!exact) {
            offerExactAlarms();
        }
    }

    private void clearReminder(int index) {
        Task t = tasks.get(index);
        Reminders.cancel(this, t.uid);
        t.remind = 0;
        save();
        refresh();
    }

    private void askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
    }

    /** Android 12+ может запрещать точные будильники — предлагаем открыть настройку. */
    private void offerExactAlarms() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage("Система не разрешает точные будильники, напоминание может "
                        + "прийти с задержкой. Открыть настройку?")
                .setNegativeButton("Не надо", null)
                .setPositiveButton("Открыть", (d, w) -> {
                    try {
                        Intent i = new Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM");
                        i.setData(android.net.Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Exception e) {
                        Toast.makeText(this, "Настройка недоступна", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    // ---------- отрисовка ----------

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, getResources().getDisplayMetrics());
    }

    private void rebuildRows() {
        rows.clear();
        for (int i = 0; i < tasks.size(); i++) {
            rows.add(new Row(i, -1));
            Task t = tasks.get(i);
            if (t.expanded) {
                for (int j = 0; j < t.subs.size(); j++) {
                    rows.add(new Row(i, j));
                }
            }
        }
    }

    private void refresh() {
        String key = dayKey();
        String d = dateFmt.format(day.getTime());
        tvDate.setText(key.equals(todayKey()) ? d + " · сегодня" : d);
        String dow = dowFmt.format(day.getTime());
        tvDow.setText(dow.substring(0, 1).toUpperCase(RU) + dow.substring(1));
        btnToday.setVisibility(key.equals(todayKey()) ? View.GONE : View.VISIBLE);

        int done = 0;
        for (Task t : tasks) {
            if (isDone(t)) {
                done++;
            }
        }
        if (tasks.isEmpty()) {
            pb.setProgress(0);
            tvProgress.setText("");
        } else {
            pb.setProgress(done * 100 / tasks.size());
            tvProgress.setText(done + "/" + tasks.size());
        }

        tvEmpty.setVisibility(tasks.isEmpty() ? View.VISIBLE : View.GONE);
        rebuildRows();
        adapter.notifyDataSetChanged();
    }

    private void strike(TextView tv, boolean on) {
        if (on) {
            tv.setPaintFlags(tv.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            tv.setAlpha(0.4f);
        } else {
            tv.setPaintFlags(tv.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
            tv.setAlpha(1f);
        }
    }

    /** Фон строки зависит от места в группе: одиночная, верх, середина, низ. */
    private void applyCard(View wrap, View card, int rowIndex) {
        Row r = rows.get(rowIndex);
        boolean groupStart = r.sub == -1;
        boolean groupEnd = rowIndex == rows.size() - 1 || rows.get(rowIndex + 1).sub == -1;

        int bg;
        if (groupStart && groupEnd) {
            bg = R.drawable.card;
        } else if (groupStart) {
            bg = R.drawable.card_top;
        } else if (groupEnd) {
            bg = R.drawable.card_bottom;
        } else {
            bg = R.drawable.card_mid;
        }
        card.setBackgroundResource(bg);
        card.setPadding(dp(6), 0, dp(4), 0);
        wrap.setPadding(0, 0, 0, groupEnd ? dp(8) : 0);
    }

    private class RowAdapter extends BaseAdapter {

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return rows.get(position).sub == -1 ? 0 : 1; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row r = rows.get(position);
            return r.sub == -1
                    ? taskView(position, r, convertView, parent)
                    : subView(position, r, convertView, parent);
        }

        private View taskView(int position, Row r, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_task, parent, false);
            }
            final Task t = tasks.get(r.task);

            CheckBox cb = row.findViewById(R.id.cbDone);
            TextView tv = row.findViewById(R.id.tvText);
            TextView from = row.findViewById(R.id.tvFrom);
            TextView count = row.findViewById(R.id.tvCount);
            Button expand = row.findViewById(R.id.btnExpand);
            Button menu = row.findViewById(R.id.btnMenu);

            applyCard(row.findViewById(R.id.wrap), row.findViewById(R.id.card), position);

            tv.setText(t.text);
            boolean done = isDone(t);
            strike(tv, done);

            List<String> meta = new ArrayList<>();
            if (t.remind > 0) {
                meta.add("\u23F0 " + timeFmt.format(new Date(t.remind)));
            }
            if (t.from != null && !done) {
                meta.add(fromLabel(t.from));
            }
            if (meta.isEmpty() || done) {
                from.setVisibility(View.GONE);
            } else {
                from.setVisibility(View.VISIBLE);
                from.setText(TextUtils.join(" · ", meta));
            }

            if (t.subs.isEmpty()) {
                count.setVisibility(View.GONE);
                expand.setVisibility(View.GONE);
            } else {
                int sd = 0;
                for (Sub s : t.subs) {
                    if (s.done) sd++;
                }
                count.setVisibility(View.VISIBLE);
                count.setText(sd + "/" + t.subs.size());
                expand.setVisibility(View.VISIBLE);
                expand.setText(t.expanded ? "▼" : "▶");
                expand.setOnClickListener(v -> {
                    t.expanded = !t.expanded;
                    save();
                    refresh();
                });
            }

            cb.setOnCheckedChangeListener(null);
            cb.setChecked(done);
            cb.setOnCheckedChangeListener((b, checked) -> {
                t.done = checked;
                for (Sub s : t.subs) {
                    s.done = checked;
                }
                if (t.remind > 0) {
                    if (checked) {
                        Reminders.cancel(MainActivity.this, t.uid);
                    } else if (t.remind > System.currentTimeMillis()) {
                        Reminders.schedule(MainActivity.this, t.uid, t.text, dayKey(), t.remind);
                    }
                }
                save();
                refresh();
            });

            tv.setOnClickListener(v -> askText("Изменить", t.text, text -> {
                t.text = text;
                save();
                refresh();
            }));
            menu.setOnClickListener(v -> taskMenu(r.task));

            return row;
        }

        private View subView(int position, Row r, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_sub, parent, false);
            }
            final Task t = tasks.get(r.task);
            final Sub s = t.subs.get(r.sub);

            CheckBox cb = row.findViewById(R.id.cbSub);
            TextView tv = row.findViewById(R.id.tvSubText);
            Button del = row.findViewById(R.id.btnSubDel);

            applyCard(row.findViewById(R.id.wrap), row.findViewById(R.id.card), position);

            tv.setText(s.text);
            strike(tv, s.done);

            cb.setOnCheckedChangeListener(null);
            cb.setChecked(s.done);
            cb.setOnCheckedChangeListener((b, checked) -> {
                s.done = checked;
                save();
                refresh();
            });

            tv.setOnClickListener(v -> askText("Изменить подпункт", s.text, text -> {
                s.text = text;
                save();
                refresh();
            }));

            final int subIndex = r.sub;
            del.setOnClickListener(v -> {
                t.subs.remove(subIndex);
                save();
                refresh();
            });

            return row;
        }
    }
}
