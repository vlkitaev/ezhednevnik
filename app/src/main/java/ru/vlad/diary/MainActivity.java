package ru.vlad.diary;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String PREFS = "diary";
    private static final Locale RU = new Locale("ru", "RU");

    private final SimpleDateFormat keyFmt = new SimpleDateFormat("yyyy-MM-dd", RU);
    private final SimpleDateFormat titleFmt = new SimpleDateFormat("EEEE, d MMMM yyyy", RU);

    private final Calendar day = Calendar.getInstance();
    private final List<Task> tasks = new ArrayList<>();

    private SharedPreferences prefs;
    private TaskAdapter adapter;
    private TextView tvDate, tvEmpty, tvProgress;
    private EditText etInput;
    private ListView list;

    static class Task {
        String text;
        boolean done;
        Task(String text, boolean done) {
            this.text = text;
            this.done = done;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        tvDate = findViewById(R.id.tvDate);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvProgress = findViewById(R.id.tvProgress);
        etInput = findViewById(R.id.etInput);
        list = findViewById(R.id.list);

        adapter = new TaskAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.btnPrev).setOnClickListener(v -> shiftDay(-1));
        findViewById(R.id.btnNext).setOnClickListener(v -> shiftDay(1));
        findViewById(R.id.btnAdd).setOnClickListener(v -> addTask());
        tvDate.setOnClickListener(v -> pickDate());
        tvDate.setOnLongClickListener(v -> {
            day.setTimeInMillis(System.currentTimeMillis());
            load();
            Toast.makeText(this, "Сегодня", Toast.LENGTH_SHORT).show();
            return true;
        });

        load();
    }

    private String dayKey() {
        return keyFmt.format(day.getTime());
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

    private void load() {
        tasks.clear();
        try {
            JSONArray arr = new JSONArray(prefs.getString(dayKey(), "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                tasks.add(new Task(o.optString("t"), o.optBoolean("d", false)));
            }
        } catch (Exception e) {
            // повреждённые данные за день — начинаем с пустого списка
        }
        refresh();
    }

    private void save() {
        JSONArray arr = new JSONArray();
        try {
            for (Task t : tasks) {
                JSONObject o = new JSONObject();
                o.put("t", t.text);
                o.put("d", t.done);
                arr.put(o);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка сохранения", Toast.LENGTH_SHORT).show();
            return;
        }
        if (tasks.isEmpty()) {
            prefs.edit().remove(dayKey()).apply();
        } else {
            prefs.edit().putString(dayKey(), arr.toString()).apply();
        }
    }

    private void addTask() {
        String text = etInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            return;
        }
        tasks.add(new Task(text, false));
        etInput.setText("");
        save();
        refresh();
        list.smoothScrollToPosition(tasks.size() - 1);
    }

    private void confirmDelete(final int position) {
        new AlertDialog.Builder(this)
                .setMessage("Удалить запись?")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (d, w) -> {
                    tasks.remove(position);
                    save();
                    refresh();
                })
                .show();
    }

    private void editTask(final int position) {
        final EditText field = new EditText(this);
        field.setText(tasks.get(position).text);
        field.setSelection(field.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("Изменить запись")
                .setView(field)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String text = field.getText().toString().trim();
                    if (!TextUtils.isEmpty(text)) {
                        tasks.get(position).text = text;
                        save();
                        refresh();
                    }
                })
                .show();
    }

    private void refresh() {
        String title = titleFmt.format(day.getTime());
        tvDate.setText(title.substring(0, 1).toUpperCase(RU) + title.substring(1));

        int done = 0;
        for (Task t : tasks) {
            if (t.done) done++;
        }
        tvProgress.setText(tasks.isEmpty() ? "" : "Выполнено " + done + " из " + tasks.size());
        tvEmpty.setVisibility(tasks.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private class TaskAdapter extends BaseAdapter {

        @Override public int getCount() { return tasks.size(); }
        @Override public Object getItem(int position) { return tasks.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_task, parent, false);
            }

            Task task = tasks.get(position);

            CheckBox cb = row.findViewById(R.id.cbDone);
            TextView tv = row.findViewById(R.id.tvText);
            Button del = row.findViewById(R.id.btnDel);

            tv.setText(task.text);
            if (task.done) {
                tv.setPaintFlags(tv.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                tv.setAlpha(0.45f);
            } else {
                tv.setPaintFlags(tv.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
                tv.setAlpha(1f);
            }

            cb.setOnCheckedChangeListener(null);
            cb.setChecked(task.done);
            cb.setOnCheckedChangeListener((b, checked) -> {
                task.done = checked;
                save();
                refresh();
            });

            tv.setOnClickListener(v -> editTask(position));
            del.setOnClickListener(v -> confirmDelete(position));

            return row;
        }
    }
}
