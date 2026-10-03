package tw.junba.transcriber;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int REQ_AUDIO = 1001;
    private static final int REQ_SAVE_TXT = 1002;
    private static final int REQ_SAVE_MD = 1003;
    private static final String PREFS = "junba_android";
    private static final String TRANSCRIBE_MODEL = "gemini-3.5-transcribe";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    private Uri audioUri;
    private String audioName = "";
    private String lastTranscript = "";
    private String lastRawResponse = "";

    private TextView audioLabel;
    private EditText apiKey;
    private Spinner modeSpinner;
    private Spinner languageSpinner;
    private CheckBox diarization;
    private CheckBox timestamps;
    private ProgressBar progress;
    private TextView stage;
    private EditText result;
    private Button startButton;
    private Button cancelButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    private View buildUi() {
        int pad = dp(16);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("峻爸 AI Transcriber v3.8.1｜Android Gemini 版", 24, true);
        root.addView(title);
        TextView note = text("手機版依手機效能採 Gemini 雲端轉錄；Windows 的 Whisper / OpenVINO / CUDA 路徑不會硬搬到手機。音訊只有在按下「開始 Gemini 轉錄」後才上傳。", 14, false);
        note.setPadding(0, dp(4), 0, dp(12));
        root.addView(note);

        Button pick = button("選擇錄音檔");
        pick.setOnClickListener(v -> chooseAudio());
        root.addView(pick, lpMatch());
        audioLabel = text("尚未選擇錄音檔", 14, false);
        audioLabel.setPadding(0, dp(6), 0, dp(12));
        root.addView(audioLabel);

        root.addView(sectionLabel("Gemini API Key"));
        apiKey = new EditText(this);
        apiKey.setSingleLine(true);
        apiKey.setHint("輸入 Google AI Studio API Key");
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        apiKey.setText(prefs.getString("api_key", ""));
        root.addView(apiKey, lpMatch());

        CheckBox showKey = new CheckBox(this);
        showKey.setText("顯示 API Key");
        showKey.setOnCheckedChangeListener((buttonView, checked) -> {
            apiKey.setInputType(InputType.TYPE_CLASS_TEXT | (checked ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            apiKey.setSelection(apiKey.length());
        });
        root.addView(showKey);

        Button saveKey = button("儲存 API Key（只存在這支手機 App）");
        saveKey.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("api_key", apiKey.getText().toString().trim()).apply();
            toast("API Key 已儲存");
        });
        root.addView(saveKey, lpMatch());

        root.addView(sectionLabel("轉錄方式"));
        modeSpinner = new Spinner(this);
        String[] modes = {"逐字稿 verbatim（可搭配多人講者／時間戳）", "智慧逐字稿 smart（閱讀優先）"};
        modeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, modes));
        root.addView(modeSpinner, lpMatch());

        languageSpinner = new Spinner(this);
        String[] langs = {"自動偵測語言", "繁體中文／華語・台語（zh-TW 提示）", "英文（en-US）", "日文（ja-JP）"};
        languageSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, langs));
        root.addView(languageSpinner, lpMatch());

        diarization = new CheckBox(this);
        diarization.setText("多人講者辨識");
        diarization.setChecked(true);
        root.addView(diarization);
        timestamps = new CheckBox(this);
        timestamps.setText("字詞時間戳（可能略降整體辨識準確率）");
        timestamps.setChecked(true);
        root.addView(timestamps);

        modeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                boolean smart = position == 1;
                diarization.setEnabled(!smart);
                timestamps.setEnabled(!smart);
                if (smart) {
                    diarization.setChecked(false);
                    timestamps.setChecked(false);
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, dp(10), 0, dp(6));
        startButton = button("開始 Gemini 轉錄");
        cancelButton = button("取消");
        cancelButton.setEnabled(false);
        actionRow.addView(startButton, lpWeight());
        actionRow.addView(cancelButton, lpWeight());
        root.addView(actionRow, lpMatch());

        startButton.setOnClickListener(v -> startTranscription());
        cancelButton.setOnClickListener(v -> {
            cancelFlag.set(true);
            setStage("已要求取消；目前網路動作結束後停止。", true);
        });

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress, lpMatch());
        stage = text("待命", 14, true);
        stage.setPadding(0, dp(4), 0, dp(8));
        root.addView(stage);

        root.addView(sectionLabel("轉錄結果"));
        result = new EditText(this);
        result.setGravity(Gravity.TOP | Gravity.START);
        result.setTextSize(17);
        result.setMinLines(12);
        result.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        result.setHint("完成後會顯示逐字稿，可直接人工修正，再另存 TXT 或 Markdown。\n\n若開啟多人講者，Gemini 會嘗試標示不同說話者。 ");
        root.addView(result, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));

        LinearLayout saveRow = new LinearLayout(this);
        saveRow.setOrientation(LinearLayout.HORIZONTAL);
        saveRow.setPadding(0, dp(10), 0, 0);
        Button saveTxt = button("另存 TXT");
        Button saveMd = button("另存 Markdown");
        saveTxt.setOnClickListener(v -> createDocument(false));
        saveMd.setOnClickListener(v -> createDocument(true));
        saveRow.addView(saveTxt, lpWeight());
        saveRow.addView(saveMd, lpWeight());
        root.addView(saveRow, lpMatch());

        TextView limits = text("Gemini 3.5 Transcribe：一般單次音訊最長可到 1 小時；啟用多人講者或字詞時間戳時，官方限制較嚴格。長錄音建議先切成 10–30 分鐘再上傳。", 13, false);
        limits.setPadding(0, dp(12), 0, 0);
        root.addView(limits);
        return scroll;
    }

    private void chooseAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        startActivityForResult(intent, REQ_AUDIO);
    }

    private void createDocument(boolean markdown) {
        String text = result.getText().toString().trim();
        if (text.isEmpty()) { toast("目前沒有可儲存的逐字稿"); return; }
        lastTranscript = text;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(markdown ? "text/markdown" : "text/plain");
        String stem = audioName.isEmpty() ? "逐字稿" : audioName.replaceFirst("\\.[^.]+$", "");
        intent.putExtra(Intent.EXTRA_TITLE, stem + (markdown ? "_逐字稿.md" : "_逐字稿.txt"));
        startActivityForResult(intent, markdown ? REQ_SAVE_MD : REQ_SAVE_TXT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_AUDIO) {
            audioUri = uri;
            audioName = displayName(uri);
            audioLabel.setText(audioName + "\n" + safeMime(uri));
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        } else if (requestCode == REQ_SAVE_TXT || requestCode == REQ_SAVE_MD) {
            boolean md = requestCode == REQ_SAVE_MD;
            String payload = md ? markdownText(result.getText().toString().trim()) : result.getText().toString().trim() + "\n";
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new Exception("無法開啟輸出檔");
                out.write(payload.getBytes(StandardCharsets.UTF_8));
                toast(md ? "Markdown 已儲存" : "TXT 已儲存");
            } catch (Exception e) {
                toast("儲存失敗：" + e.getMessage());
            }
        }
    }

    private void startTranscription() {
        if (audioUri == null) { toast("請先選擇錄音檔"); return; }
        String key = apiKey.getText().toString().trim();
        if (key.isEmpty()) { toast("請先輸入 Gemini API Key"); return; }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("api_key", key).apply();
        cancelFlag.set(false);
        startButton.setEnabled(false);
        cancelButton.setEnabled(true);
        progress.setVisibility(View.VISIBLE);
        result.setText("");
        setStage("準備音檔…", false);

        executor.submit(() -> {
            File temp = null;
            String uploadedName = null;
            try {
                temp = copyUriToCache(audioUri);
                if (cancelFlag.get()) throw new InterruptedException("已取消");
                String mime = safeMime(audioUri);
                setStage("上傳音訊至 Gemini…", false);
                JSONObject uploaded = uploadFile(key, temp, mime);
                JSONObject fileObj = uploaded.optJSONObject("file");
                if (fileObj == null) throw new Exception("Files API 沒有回傳 file 資訊：" + uploaded);
                String fileUri = fileObj.optString("uri", "");
                uploadedName = fileObj.optString("name", "");
                if (fileUri.isEmpty()) throw new Exception("Files API 沒有回傳 file URI");
                if (cancelFlag.get()) throw new InterruptedException("已取消");

                setStage("Gemini 3.5 Transcribe 辨識中…", false);
                JSONObject response = transcribe(key, fileUri, mime);
                lastRawResponse = response.toString(2);
                String text = extractOutputText(response).trim();
                if (text.isEmpty()) text = "【未能從回應中解析出文字】\n\n" + lastRawResponse;
                lastTranscript = text;
                String finalText = text;
                runOnUiThread(() -> result.setText(finalText));
                setStage("轉錄完成", false);
            } catch (InterruptedException e) {
                setStage("已取消", true);
            } catch (Exception e) {
                setStage("失敗：" + e.getClass().getSimpleName() + "：" + e.getMessage(), true);
            } finally {
                if (uploadedName != null && !uploadedName.isEmpty()) {
                    try { deleteUploaded(key, uploadedName); } catch (Exception ignored) {}
                }
                if (temp != null) temp.delete();
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    startButton.setEnabled(true);
                    cancelButton.setEnabled(false);
                });
            }
        });
    }

    private File copyUriToCache(Uri uri) throws Exception {
        String ext = ".audio";
        String n = displayName(uri);
        int dot = n.lastIndexOf('.');
        if (dot >= 0 && dot < n.length() - 1) ext = n.substring(dot);
        File dst = File.createTempFile("junba_", ext, getCacheDir());
        try (InputStream in = new BufferedInputStream(getContentResolver().openInputStream(uri));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
            if (in == null) throw new Exception("無法讀取選取的音檔");
            byte[] buf = new byte[256 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                if (cancelFlag.get()) throw new InterruptedException("已取消");
                out.write(buf, 0, r);
            }
        }
        return dst;
    }

    private JSONObject uploadFile(String key, File file, String mime) throws Exception {
        URL startUrl = new URL("https://generativelanguage.googleapis.com/upload/v1beta/files");
        HttpURLConnection start = (HttpURLConnection) startUrl.openConnection();
        start.setRequestMethod("POST");
        start.setConnectTimeout(30_000);
        start.setReadTimeout(60_000);
        start.setDoOutput(true);
        start.setRequestProperty("x-goog-api-key", key);
        start.setRequestProperty("X-Goog-Upload-Protocol", "resumable");
        start.setRequestProperty("X-Goog-Upload-Command", "start");
        start.setRequestProperty("X-Goog-Upload-Header-Content-Length", String.valueOf(file.length()));
        start.setRequestProperty("X-Goog-Upload-Header-Content-Type", mime);
        start.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        JSONObject meta = new JSONObject().put("file", new JSONObject().put("display_name", audioName.isEmpty() ? "audio" : audioName));
        writeUtf8(start, meta.toString());
        int sc = start.getResponseCode();
        if (sc < 200 || sc >= 300) throw new Exception("建立上傳工作失敗 HTTP " + sc + "：" + readResponse(start));
        String uploadUrl = start.getHeaderField("X-Goog-Upload-URL");
        if (uploadUrl == null || uploadUrl.isEmpty()) uploadUrl = start.getHeaderField("x-goog-upload-url");
        start.disconnect();
        if (uploadUrl == null || uploadUrl.isEmpty()) throw new Exception("Gemini 未回傳 upload URL");

        HttpURLConnection up = (HttpURLConnection) new URL(uploadUrl).openConnection();
        up.setRequestMethod("POST");
        up.setConnectTimeout(30_000);
        up.setReadTimeout(180_000);
        up.setDoOutput(true);
        up.setFixedLengthStreamingMode(file.length());
        up.setRequestProperty("Content-Length", String.valueOf(file.length()));
        up.setRequestProperty("X-Goog-Upload-Offset", "0");
        up.setRequestProperty("X-Goog-Upload-Command", "upload, finalize");
        up.setRequestProperty("Content-Type", mime);
        try (InputStream in = new BufferedInputStream(new FileInputStream(file)); OutputStream out = new BufferedOutputStream(up.getOutputStream())) {
            byte[] buf = new byte[256 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                if (cancelFlag.get()) throw new InterruptedException("已取消");
                out.write(buf, 0, r);
            }
        }
        int code = up.getResponseCode();
        String body = readResponse(up);
        up.disconnect();
        if (code < 200 || code >= 300) throw new Exception("音訊上傳失敗 HTTP " + code + "：" + body);
        return new JSONObject(body);
    }

    private JSONObject transcribe(String key, String fileUri, String mime) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/interactions").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(240_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("x-goog-api-key", key);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        JSONObject audio = new JSONObject()
                .put("type", "audio")
                .put("uri", fileUri)
                .put("mime_type", mime);
        JSONArray input = new JSONArray().put(audio);
        JSONObject tc = new JSONObject();

        if (modeSpinner.getSelectedItemPosition() == 1) {
            tc.put("mode", "smart");
        } else {
            JSONObject mode = new JSONObject().put("type", "verbatim");
            if (diarization.isChecked()) mode.put("diarization_mode", "speaker");
            if (timestamps.isChecked()) mode.put("timestamp_granularities", new JSONArray().put("word"));
            tc.put("mode", mode);
        }
        String lang = languageCode();
        if (!lang.isEmpty()) tc.put("language_codes", new JSONArray().put(lang));

        JSONObject body = new JSONObject()
                .put("model", TRANSCRIBE_MODEL)
                .put("input", input)
                .put("generation_config", new JSONObject().put("transcription_config", tc));
        writeUtf8(conn, body.toString());
        int code = conn.getResponseCode();
        String response = readResponse(conn);
        conn.disconnect();
        if (code < 200 || code >= 300) throw new Exception("Gemini 轉錄失敗 HTTP " + code + "：" + response);
        return new JSONObject(response);
    }

    private void deleteUploaded(String key, String name) throws Exception {
        String path = name.startsWith("files/") ? name : "files/" + name;
        HttpURLConnection c = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/" + path).openConnection();
        c.setRequestMethod("DELETE");
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("x-goog-api-key", key);
        try { c.getResponseCode(); } finally { c.disconnect(); }
    }

    private static void writeUtf8(HttpURLConnection c, String s) throws Exception {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
    }

    private static String readResponse(HttpURLConnection c) throws Exception {
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
        }
        return b.toString().trim();
    }

    private String extractOutputText(JSONObject root) {
        String v = root.optString("output_text", "");
        if (!v.isEmpty()) return v;
        JSONArray outputs = root.optJSONArray("outputs");
        if (outputs != null) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < outputs.length(); i++) {
                JSONObject o = outputs.optJSONObject(i);
                if (o != null && "text".equalsIgnoreCase(o.optString("type"))) {
                    String t = o.optString("text", "");
                    if (!t.isEmpty()) b.append(t).append('\n');
                }
            }
            if (b.length() > 0) return b.toString();
        }
        JSONArray steps = root.optJSONArray("steps");
        if (steps != null) {
            for (int i = steps.length() - 1; i >= 0; i--) {
                JSONObject step = steps.optJSONObject(i);
                if (step == null) continue;
                JSONArray content = step.optJSONArray("content");
                if (content == null) continue;
                StringBuilder b = new StringBuilder();
                for (int j = 0; j < content.length(); j++) {
                    JSONObject part = content.optJSONObject(j);
                    if (part != null) {
                        String t = part.optString("text", "");
                        if (!t.isEmpty()) b.append(t).append('\n');
                    }
                }
                if (b.length() > 0) return b.toString();
            }
        }
        return findTextRecursive(root, 0);
    }

    private String findTextRecursive(Object obj, int depth) {
        if (obj == null || depth > 8) return "";
        if (obj instanceof JSONObject) {
            JSONObject o = (JSONObject) obj;
            String direct = o.optString("text", "");
            if (!direct.isEmpty()) return direct;
            JSONArray names = o.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String key = names.optString(i);
                    String found = findTextRecursive(o.opt(key), depth + 1);
                    if (!found.isEmpty()) return found;
                }
            }
        } else if (obj instanceof JSONArray) {
            JSONArray a = (JSONArray) obj;
            for (int i = 0; i < a.length(); i++) {
                String found = findTextRecursive(a.opt(i), depth + 1);
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    private String markdownText(String transcript) {
        String stem = audioName.isEmpty() ? "逐字稿" : audioName.replaceFirst("\\.[^.]+$", "");
        return "# " + stem + "_逐字稿\n\n" +
                "- 來源音檔：" + (audioName.isEmpty() ? "—" : audioName) + "\n" +
                "- 辨識引擎：" + TRANSCRIBE_MODEL + "\n\n" +
                "## 逐字稿\n\n" + transcript + "\n";
    }

    private String languageCode() {
        switch (languageSpinner.getSelectedItemPosition()) {
            case 1: return "zh-TW";
            case 2: return "en-US";
            case 3: return "ja-JP";
            default: return "";
        }
    }

    private String safeMime(Uri uri) {
        String m = getContentResolver().getType(uri);
        if (m == null || m.trim().isEmpty()) {
            String n = displayName(uri).toLowerCase(Locale.ROOT);
            if (n.endsWith(".m4a")) return "audio/mp4";
            if (n.endsWith(".mp3")) return "audio/mpeg";
            if (n.endsWith(".wav")) return "audio/wav";
            if (n.endsWith(".aac")) return "audio/aac";
            if (n.endsWith(".flac")) return "audio/flac";
            if (n.endsWith(".ogg")) return "audio/ogg";
            return "audio/mp4";
        }
        return m;
    }

    private String displayName(Uri uri) {
        String name = "audio";
        try (android.database.Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {}
        return name == null ? "audio" : name;
    }

    private void setStage(String message, boolean warn) {
        runOnUiThread(() -> {
            stage.setText(message);
            stage.setTextColor(warn ? 0xFFB71C1C : 0xFF1565C0);
        });
    }

    private TextView sectionLabel(String s) {
        TextView t = text(s, 16, true);
        t.setPadding(0, dp(12), 0, dp(4));
        return t;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(0xFF17202A);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setMinHeight(dp(48));
        return b;
    }

    private LinearLayout.LayoutParams lpMatch() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams lpWeight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private int dp(int x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override
    protected void onDestroy() {
        cancelFlag.set(true);
        executor.shutdownNow();
        super.onDestroy();
    }
}
