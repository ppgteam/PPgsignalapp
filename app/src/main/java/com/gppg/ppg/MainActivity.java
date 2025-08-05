package com.gppg.ppg;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/** Alçak Geçiren Filtre (IIR) */
class LowPassFilter {
    private static final float ALPHA = 0.5f;
    private float lastFilteredValue = 0f;
    private boolean isFirstValue = true;

    public float apply(float newValue) {
        if (isFirstValue) {
            lastFilteredValue = newValue;
            isFirstValue = false;
        } else {
            lastFilteredValue = ALPHA * newValue + (1.0f - ALPHA) * lastFilteredValue;
        }
        return lastFilteredValue;
    }
}

/** Hareketli Ortalama Filtresi (Moving Average) */
class MovingAverageFilter {
    private final int windowSize;
    private final float[] window;
    private int pointer = 0;
    private int count = 0;
    private float sum = 0;

    public MovingAverageFilter(int windowSize) {
        this.windowSize = windowSize;
        this.window = new float[windowSize];
    }

    public float apply(float newValue) {
        sum -= window[pointer];
        window[pointer] = newValue;
        sum += newValue;
        pointer = (pointer + 1) % windowSize;
        if (count < windowSize) count++;
        return sum / count;
    }
}

/** Yüksek Geçiren Filtre (High Pass) */
class HighPassFilter {
    private static final float ALPHA = 0.975f; // Bu değeri sinyal şekline göre değiştirin
    private float prevRaw = 0f;
    private float prevFiltered = 0f;

    public float apply(float newValue) {
        float filtered = ALPHA * (prevFiltered + newValue - prevRaw);
        prevRaw = newValue;
        prevFiltered = filtered;
        return filtered;
    }
}

public class MainActivity extends AppCompatActivity {

    // *** START OF MODIFICATION ***
    // تحديد طرق التسجيل المتاحة
    private enum RecordingMethod {
        FOUR_ZONES_FILTERED, // الطريقة الأصلية: 4 مناطق مع فلاتر
        CENTER_BOX_RAW       // الطريقة الجديدة: مربع في المنتصف بدون فلاتر
    }
    private RecordingMethod currentRecordingMethod = RecordingMethod.FOUR_ZONES_FILTERED; // الطريقة الافتراضية
    // *** END OF MODIFICATION ***

    private enum AppState {
        IDLE,
        WAITING_FOR_FINGER,
        STABILIZING,
        RECORDING,
        WAITING_FOR_INPUT
    }

    private AppState currentState = AppState.IDLE;

    // Arayüz elemanları
    private View colorView;
    private SeekBar hueSeekBar;
    private SeekBar brightnessSeekBar;
    private TextView brightnessValueText;
    private PreviewView previewView;
    private LineChart realtimeChart;
    private Button newRecordButton;
    private Button saveButton;
    private ConstraintLayout recordingLayout;
    private TextView instructionsTextView;
    private EditText bloodSugarEditText;
    private SwitchMaterial recordingMethodSwitch; // *** MODIFICATION: إضافة متغير لمفتاح التبديل

    // Kamera ve analiz değişkenleri
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ExecutorService cameraExecutor;
    private LowPassFilter filter;
    private MovingAverageFilter maFilter;
    private HighPassFilter hpFilter;
    private Toast statusToast = null;

    // Kayıt süreci değişkenleri
    private CountDownTimer stabilizationTimer;
    private CountDownTimer recordingTimer;
    private List<Float> recordedPpgData;

    private static final int CAMERA_PERMISSION_REQUEST_CODE = 101;
    private static final int STORAGE_PERMISSION_REQUEST_CODE = 102;
    private static final String FOLDER_NAME = "PPG_Signals";


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        setContentView(R.layout.activity_main);

        bindViews();
        filter = new LowPassFilter();
        maFilter = new MovingAverageFilter(4);
        hpFilter = new HighPassFilter();
        recordedPpgData = new ArrayList<>();

        setupListeners();
        checkSystemWritePermission();
        setupChart();
        cameraExecutor = Executors.newSingleThreadExecutor();
        checkCameraPermissionAndStartCamera();

        resetToIdleState();
    }

    private void bindViews() {
        colorView = findViewById(R.id.colorView);
        hueSeekBar = findViewById(R.id.hueSeekBar);
        brightnessSeekBar = findViewById(R.id.brightnessSeekBar);
        brightnessValueText = findViewById(R.id.brightnessValueText);
        previewView = findViewById(R.id.camera_preview);
        realtimeChart = findViewById(R.id.realtime_chart);
        newRecordButton = findViewById(R.id.newRecordButton);
        saveButton = findViewById(R.id.saveButton);
        recordingLayout = findViewById(R.id.recordingLayout);
        instructionsTextView = findViewById(R.id.instructionsTextView);
        bloodSugarEditText = findViewById(R.id.bloodSugarEditText);
        recordingMethodSwitch = findViewById(R.id.recordingMethodSwitch); // *** MODIFICATION: ربط مفتاح التبديل
    }

    private void setupListeners() {
        hueSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float currentHue = (float) progress;
                float[] hsv = {currentHue, 1.0f, 1.0f};
                colorView.setBackgroundColor(Color.HSVToColor(hsv));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        newRecordButton.setOnClickListener(v -> {
            currentState = AppState.WAITING_FOR_FINGER;
            updateUiForState();
            resetMeasurementProcess();
        });

        saveButton.setOnClickListener(v -> checkAndRequestStoragePermission());

        bloodSugarEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (currentState == AppState.WAITING_FOR_INPUT) {
                    saveButton.setEnabled(s.length() > 0);
                }
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        // *** START OF MODIFICATION ***
        // إضافة مستمع لمفتاح تبديل طريقة التسجيل
        recordingMethodSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                currentRecordingMethod = RecordingMethod.CENTER_BOX_RAW;
                recordingMethodSwitch.setText("طريقة التسجيل: مربع مركزي خام");
                Toast.makeText(this, "تم التغيير إلى: مربع مركزي خام", Toast.LENGTH_SHORT).show();
            } else {
                currentRecordingMethod = RecordingMethod.FOUR_ZONES_FILTERED;
                recordingMethodSwitch.setText("طريقة التسجيل: 4 مناطق مفلترة");
                Toast.makeText(this, "تم التغيير إلى: 4 مناطق مفلترة", Toast.LENGTH_SHORT).show();
            }
        });
        // *** END OF MODIFICATION ***
    }

    private void updateUiForState() {
        runOnUiThread(() -> {
            switch (currentState) {
                case IDLE:
                    newRecordButton.setVisibility(View.VISIBLE);
                    recordingMethodSwitch.setVisibility(View.VISIBLE); // [تعديل] تغيير setEnabled إلى setVisibility
                    recordingLayout.setVisibility(View.GONE);
                    // تم نقل setEnabled(true) إلى مكان آخر لأنه لا حاجة له هنا طالما أن العنصر ظاهر
                    break;
                case WAITING_FOR_FINGER:
                case STABILIZING:
                case RECORDING:
                    newRecordButton.setVisibility(View.GONE);
                    recordingMethodSwitch.setVisibility(View.GONE); // [تعديل] إخفاء المفتاح بدلاً من تعطيله
                    recordingLayout.setVisibility(View.VISIBLE);
                    bloodSugarEditText.setEnabled(false);
                    saveButton.setEnabled(false);
                    // لا داعي لـ setEnabled(false) للمفتاح طالما أنه مخفي
                    break;
                case WAITING_FOR_INPUT:
                    newRecordButton.setVisibility(View.GONE);
                    recordingMethodSwitch.setVisibility(View.GONE); // [تعديل] إخفاء المفتاح بدلاً من تعطيله
                    recordingLayout.setVisibility(View.VISIBLE);
                    bloodSugarEditText.setEnabled(true);
                    saveButton.setEnabled(bloodSugarEditText.getText().length() > 0);
                    // لا داعي لـ setEnabled(false) للمفتاح طالما أنه مخفي
                    break;
            }
        });
    }

    private void resetMeasurementProcess() {
        if (stabilizationTimer != null) stabilizationTimer.cancel();
        if (recordingTimer != null) recordingTimer.cancel();

        recordedPpgData.clear();
        if (realtimeChart.getData() != null) {
            realtimeChart.getData().clearValues();
            realtimeChart.notifyDataSetChanged();
            realtimeChart.invalidate();
        }

        instructionsTextView.setText("Parmağınızı kameraya doğru şekilde yerleştirin");
        bloodSugarEditText.setText("");

        if (currentState != AppState.IDLE) {
            currentState = AppState.WAITING_FOR_FINGER;
        }
        updateUiForState();
    }

    private void resetToIdleState() {
        currentState = AppState.IDLE;
        resetMeasurementProcess();
        updateUiForState();
        instructionsTextView.setText("Yeni bir kayıt başlatmak için basın");
    }

    private void startStabilizationTimer() {
        currentState = AppState.STABILIZING;
        stabilizationTimer = new CountDownTimer(10000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                instructionsTextView.setText("Sinyal iyi! Sabit tutun... " + (millisUntilFinished / 1000));
            }
            @Override
            public void onFinish() {
                startRecordingTimer();
            }
        }.start();
    }

    private void startRecordingTimer() {
        currentState = AppState.RECORDING;
        recordedPpgData.clear();

        recordingTimer = new CountDownTimer(30000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                instructionsTextView.setText("Kayıt yapılıyor... " + (millisUntilFinished / 1000));
            }
            @Override
            public void onFinish() {
                currentState = AppState.WAITING_FOR_INPUT;
                instructionsTextView.setText("Kayıt tamamlandı. Kan şekeri değerini girin ve kaydet'e basın.");
                updateUiForState();
                Toast.makeText(MainActivity.this, "30 saniyelik veri başarıyla kaydedildi", Toast.LENGTH_SHORT).show();
            }
        }.start();
    }

    @SuppressLint("UnsafeOptInUsageError")
    void bindPreview(@NonNull ProcessCameraProvider cameraProvider) {
        cameraProvider.unbindAll();

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(120, 160))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        final float MIN_RED_AVG = 120f;
        final float MIN_LUMINANCE_AVG = 10f;

        imageAnalysis.setAnalyzer(cameraExecutor, image -> {
            ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
            ImageProxy.PlaneProxy vPlane = image.getPlanes()[2];
            float avgLuminance = calculateAverage(yPlane.getBuffer());
            float avgRed = calculateAverage(vPlane.getBuffer());

            boolean isFingerDetected = (avgRed > MIN_RED_AVG && avgLuminance > MIN_LUMINANCE_AVG);

            if (isFingerDetected) {
                // *** START OF MODIFICATION ***
                // التحقق من طريقة التسجيل المختارة
                if (currentRecordingMethod == RecordingMethod.CENTER_BOX_RAW) {
                    // --- الطريقة الجديدة: مربع مركزي خام ---
                    ByteBuffer buffer = vPlane.getBuffer();
                    int rowStride = vPlane.getRowStride();
                    int pixelStride = vPlane.getPixelStride();
                    int planeWidth = image.getWidth() / 2;
                    int planeHeight = image.getHeight() / 2;

                    // تحديد حجم المربع المركزي (مثال: 40% من العرض والارتفاع)
                    int boxWidth = (int) (planeWidth * 0.40);
                    int boxHeight = (int) (planeHeight * 0.40);

                    // حساب إحداثيات المربع
                    int startX = (planeWidth - boxWidth) / 2;
                    int startY = (planeHeight - boxHeight) / 2;
                    int endX = startX + boxWidth;
                    int endY = startY + boxHeight;

                    long sum = 0;
                    int count = 0;

                    for (int y = startY; y < endY; y++) {
                        for (int x = startX; x < endX; x++) {
                            int pos = y * rowStride + x * pixelStride;
                            if (pos >= buffer.limit()) continue;
                            sum += buffer.get(pos) & 0xFF;
                            count++;
                        }
                    }

                    float average = (count > 0) ? (float) -sum / count : 0;
                    final float[] dataToSend = new float[]{average};

                    // بما أنه لا يوجد فلاتر، نعتبر الإشارة جيدة طالما الإصبع موجود
                    runOnUiThread(() -> handleSignalState(true, dataToSend));

                } else {
                    // --- الطريقة الأصلية: 4 مناطق مفلترة ---
                    ByteBuffer buffer = vPlane.getBuffer();
                    int rowStride = vPlane.getRowStride();
                    int pixelStride = vPlane.getPixelStride();
                    int planeWidth = image.getWidth() / 2;
                    int planeHeight = image.getHeight() / 2;
                    int cropMarginX = (int) (planeWidth * 0.10);
                    int startX = cropMarginX;
                    int endX = planeWidth - cropMarginX;
                    int cropMarginY = (int) (planeHeight * 0.20);
                    int startY = cropMarginY;
                    int endY = planeHeight - cropMarginY;
                    int centralRegionHeight = endY - startY;
                    if (centralRegionHeight < 4) {
                        image.close();
                        return;
                    }
                    int sliceHeight = centralRegionHeight / 4;
                    long[] sums = new long[4];
                    int[] counts = new int[4];

                    for (int y = startY; y < endY; y++) {
                        for (int x = startX; x < endX; x++) {
                            int sliceIndex = 3 - ((y - startY) / sliceHeight);
                            sliceIndex = Math.max(0, Math.min(3, sliceIndex));
                            int pos = y * rowStride + x * pixelStride;
                            if (pos >= buffer.limit()) continue;
                            int pixelValue = buffer.get(pos) & 0xFF;
                            sums[sliceIndex] += pixelValue;
                            counts[sliceIndex]++;
                        }
                    }
                    final float[] averages = new float[4];
                    for (int i = 0; i < 4; i++) {
                        averages[i] = (counts[i] > 0) ? (float) -sums[i] / counts[i] : 0;
                    }
                    final float[] filteredAverages = new float[4];
                    boolean isSignalGood = true;
                    for (int i = 0; i < 4; i++) {
                        float lowPassed = filter.apply(averages[i]);
                        float maPassed = maFilter.apply(lowPassed);
                        float finalFiltered = hpFilter.apply(maPassed);
                        filteredAverages[i] = finalFiltered;
                        if (finalFiltered < -1.0f || finalFiltered > 1.0f) {
                            isSignalGood = false;
                        }
                    }
                    boolean finalIsSignalGood = isSignalGood;
                    runOnUiThread(() -> handleSignalState(finalIsSignalGood, filteredAverages));
                }
                // *** END OF MODIFICATION ***

            } else {
                runOnUiThread(() -> handleSignalState(false, null));
            }
            image.close();
        });

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build();
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
    }

    private void handleSignalState(boolean isSignalGood, float[] data) {
        if (currentState == AppState.IDLE || currentState == AppState.WAITING_FOR_INPUT) {
            return;
        }

        if (isSignalGood) {
            realtimeChart.setVisibility(View.VISIBLE);
            if (statusToast != null) statusToast.cancel();

            // الطريقة الجديدة سترسل قيمة واحدة، والطريقة القديمة 4 قيم
            // هذا الكود يتعامل مع كلتا الحالتين
            for (float value : data) {
                addChartEntry(value);
            }

            if (currentState == AppState.WAITING_FOR_FINGER) {
                startStabilizationTimer();
            } else if (currentState == AppState.RECORDING) {
                for (float value : data) {
                    recordedPpgData.add(value);
                }
            }
        } else {
            realtimeChart.setVisibility(View.INVISIBLE);
            showStatusToast("Lütfen parmağınızın konumunu ayarlayın veya kamerayı tamamen kapatın");

            if (currentState == AppState.STABILIZING || currentState == AppState.RECORDING) {
                Toast.makeText(this, "Sinyal kesildi! İşlem yeniden başlatılıyor.", Toast.LENGTH_SHORT).show();
                resetMeasurementProcess();
            }
        }
    }

    private void showStatusToast(String message) {
        if (statusToast == null) {
            statusToast = Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT);
        } else {
            statusToast.setText(message);
        }
        statusToast.show();
    }

    private void saveDataToCsv() {
        saveButton.setEnabled(false);

        String bloodSugarValue = bloodSugarEditText.getText().toString();
        if (bloodSugarValue.isEmpty()) {
            Toast.makeText(this, "Lütfen önce kan şekeri değerini girin!", Toast.LENGTH_SHORT).show();
            saveButton.setEnabled(true);
            return;
        }

        if (recordedPpgData.isEmpty()) {
            Toast.makeText(this, "Kaydedilecek veri bulunmuyor!", Toast.LENGTH_SHORT).show();
            resetToIdleState();
            return;
        }

        StringBuilder fileContent = new StringBuilder();

        // *** START OF MODIFICATION ***
        // إضافة معلومات عن طريقة التسجيل إلى الملف
        fileContent.append("recording_method\n");
        fileContent.append(currentRecordingMethod.name()).append("\n");
        // *** END OF MODIFICATION ***

        fileContent.append("blood_sugar_value\n");
        fileContent.append(bloodSugarValue).append("\n");

        fileContent.append("ppg_data\n"); // إضافة عنوان لبيانات PPG
        for (Float dataPoint : recordedPpgData) {
            fileContent.append(dataPoint).append("\n");
        }


        try {
            File documentsFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            File appFolder = new File(documentsFolder, FOLDER_NAME);
            if (!appFolder.exists() && !appFolder.mkdirs()) {
                Log.e("SaveFile", "Klasör oluşturulamadı!");
                Toast.makeText(this, "Klasör oluşturma başarısız", Toast.LENGTH_SHORT).show();
                runOnUiThread(() -> saveButton.setEnabled(true));
                return;
            }

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "PPG_Signal_" + timeStamp + ".csv";
            File file = new File(appFolder, fileName);

            FileWriter writer = new FileWriter(file);
            writer.write(fileContent.toString());
            writer.flush();
            writer.close();

            Toast.makeText(this, "Başarıyla kaydedildi: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
            Log.d("SaveFile", "Dosya başarıyla kaydedildi: " + file.getAbsolutePath());

            resetToIdleState();

        } catch (IOException e) {
            Log.e("SaveFile", "Dosya kaydedilirken hata oluştu", e);
            Toast.makeText(this, "Dosya kaydedilemedi: " + e.getMessage(), Toast.LENGTH_LONG).show();
            if (currentState != AppState.IDLE) {
                runOnUiThread(() -> saveButton.setEnabled(true));
            }
        }
    }

    // --- باقي الدوال تبقى كما هي بدون تغيير ---
    // --- The rest of the functions remain unchanged ---

    private void setupChart() {
        realtimeChart.getDescription().setEnabled(false);
        realtimeChart.setTouchEnabled(true);
        realtimeChart.setDragEnabled(true);
        realtimeChart.setScaleEnabled(true);
        realtimeChart.setDrawGridBackground(false);
        realtimeChart.setPinchZoom(true);
        realtimeChart.setBackgroundColor(Color.TRANSPARENT);
        realtimeChart.setVisibility(View.INVISIBLE);

        LineData data = new LineData();
        data.setValueTextColor(Color.WHITE);
        realtimeChart.setData(data);

        XAxis xl = realtimeChart.getXAxis();
        xl.setTextColor(Color.DKGRAY);
        xl.setDrawGridLines(false);
        xl.setAvoidFirstLastClipping(true);
        xl.setEnabled(true);
        xl.setPosition(XAxis.XAxisPosition.BOTTOM);

        YAxis leftAxis = realtimeChart.getAxisLeft();
        leftAxis.setTextColor(Color.DKGRAY);
        leftAxis.setDrawGridLines(true);

        realtimeChart.getAxisRight().setEnabled(false);
        realtimeChart.getLegend().setEnabled(false);
    }

    private void addChartEntry(float value) {
        LineData data = realtimeChart.getData();
        if (data == null) return;

        ILineDataSet set = data.getDataSetByIndex(0);
        if (set == null) {
            set = createSet();
            data.addDataSet(set);
        }

        data.addEntry(new Entry(set.getEntryCount(), value), 0);
        data.notifyDataChanged();
        realtimeChart.notifyDataSetChanged();

        final int lookbackCount = 200;
        int entryCount = set.getEntryCount();

        if (entryCount < 2) {
            realtimeChart.moveViewToX(data.getEntryCount());
            return;
        }

        int startIndex = Math.max(0, entryCount - lookbackCount);
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;

        for (int i = startIndex; i < entryCount; i++) {
            Entry entry = set.getEntryForIndex(i);
            if (entry != null) {
                if (entry.getY() < minY) minY = entry.getY();
                if (entry.getY() > maxY) maxY = entry.getY();
            }
        }

        float range = maxY - minY;
        if (range < 0.1f) range = 0.1f;
        float padding = range * 0.20f;

        YAxis leftAxis = realtimeChart.getAxisLeft();
        leftAxis.setAxisMinimum(minY - padding);
        leftAxis.setAxisMaximum(maxY + padding);

        realtimeChart.setVisibleXRangeMaximum(200);
        realtimeChart.moveViewToX(data.getEntryCount());
    }

    private LineDataSet createSet() {
        LineDataSet set = new LineDataSet(null, "PPG Sinyali");
        set.setAxisDependency(YAxis.AxisDependency.LEFT);
        set.setColor(Color.RED);
        set.setLineWidth(2f);
        set.setDrawCircles(false);
        set.setDrawValues(false);
        set.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        return set;
    }

    private float calculateAverage(ByteBuffer buffer) {
        long sum = 0;
        int count = 0;
        buffer.rewind();
        while (buffer.hasRemaining()) {
            sum += buffer.get() & 0xFF;
            count++;
        }
        return (count > 0) ? (float) sum / count : 0;
    }

    private void checkCameraPermissionAndStartCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        }
    }

    private void startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindPreview(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e("CameraX", "Kamera başlatılamadı.", e);
                Toast.makeText(this, "Kamera başlatılamadı", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void checkSystemWritePermission() {
        boolean permissionGranted = (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) || Settings.System.canWrite(this);
        if (permissionGranted) {
            setupBrightnessSlider();
        } else {
            showPermissionDialog();
        }
    }

    private void showPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("İzin Gerekli")
                .setMessage("Uygulamanın ekran parlaklığını kontrol edebilmesi için sistem ayarlarını değiştirme iznine ihtiyacı var.")
                .setPositiveButton("Ayarlara Git", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .setNegativeButton("İptal", (dialog, which) -> {
                    Toast.makeText(this, "Bu izin olmadan ekran parlaklığı kontrol edilemez.", Toast.LENGTH_LONG).show();
                    brightnessSeekBar.setEnabled(false);
                    brightnessValueText.setText("Mevcut Değil");
                })
                .setCancelable(false)
                .show();
    }

    private void setupBrightnessSlider() {
        brightnessSeekBar.setEnabled(true);
        brightnessSeekBar.setMax(255);
        try {
            int currentSystemBrightness = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            brightnessSeekBar.setProgress(currentSystemBrightness);
            updateBrightnessText(currentSystemBrightness);
        } catch (Settings.SettingNotFoundException e) {
            e.printStackTrace();
        }

        brightnessSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, progress);
                    updateBrightnessText(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void updateBrightnessText(int brightnessValue) {
        int percentage = (int) ((brightnessValue / 255.0) * 100);
        brightnessValueText.setText(percentage + "%");
    }

    private void checkAndRequestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.addCategory("android.intent.category.DEFAULT");
                    intent.setData(Uri.parse(String.format("package:%s", getApplicationContext().getPackageName())));
                    startActivityForResult(intent, STORAGE_PERMISSION_REQUEST_CODE);
                } catch (Exception e) {
                    Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(intent, STORAGE_PERMISSION_REQUEST_CODE);
                }
            } else {
                saveDataToCsv();
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        STORAGE_PERMISSION_REQUEST_CODE);
            } else {
                saveDataToCsv();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case CAMERA_PERMISSION_REQUEST_CODE:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startCamera();
                } else {
                    Toast.makeText(this, "Kamera izni gereklidir.", Toast.LENGTH_LONG).show();
                }
                break;
            case STORAGE_PERMISSION_REQUEST_CODE:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Depolama izni verildi.", Toast.LENGTH_SHORT).show();
                    saveDataToCsv();
                } else {
                    Toast.makeText(this, "Dosyayı kaydetmek için depolama izni gereklidir.", Toast.LENGTH_LONG).show();
                }
                break;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "Dosya erişim izni verildi.", Toast.LENGTH_SHORT).show();
                    saveDataToCsv();
                } else {
                    Toast.makeText(this, "Kaydetmek için dosya erişim izni gereklidir.", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkSystemWritePermission();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (stabilizationTimer != null) stabilizationTimer.cancel();
        if (recordingTimer != null) recordingTimer.cancel();
    }
}