package com.kaarel.teepilt;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

public class MainActivity extends AppCompatActivity {

    private final Executor cameraExecutor = Executors.newSingleThreadExecutor();
    private final Executor networkExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final int REQUEST_CODE_PERMISSIONS = 1001;
    private static final String[] REQUIRED_PERMISSIONS = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        ? new String[]{Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.WRITE_EXTERNAL_STORAGE}
        : new String[]{Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION};

    private LocationManager locationManager;
    //https://kaine.ee/tram/minimal.php?x=675821.32&y=6466772.75
    private volatile double myLatitude = 675821.32;
    private volatile double myLongitude = 6466772.75;

    private TextView textView;
    private volatile String locationOnRoad = "";
    private volatile String locationOnRoadSimple = "";

    private PreviewView mPreviewView;
    private ImageView captureImage;
    private ImageView galleryImage;
    private Camera camera;
    private ScaleGestureDetector scaleGestureDetector;
    private Uri lastSavedUri = null;

    private final Runnable updateTextViewRunnable = new Runnable() {
        @Override
        public void run() {
            textView.setText(locationOnRoad + "\n" + String.format(Locale.US, "%.2f, %.2f  ", myLatitude, myLongitude) +"/ "+ getDate());
            mainHandler.postDelayed(this, 1000);
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            double[] lest97 = wgs84ToLest97(location.getLatitude(), location.getLongitude());
            myLatitude = lest97[0];
            myLongitude = lest97[1];
            loadRoadLocation(myLatitude, myLongitude);
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {}

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mPreviewView = findViewById(R.id.previewView);
        captureImage = findViewById(R.id.captureImg);
        galleryImage = findViewById(R.id.openGallery);
        textView = findViewById(R.id.textView);

        galleryImage.setOnClickListener(v -> {
            if (lastSavedUri != null) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(lastSavedUri, "image/jpeg");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
            } else {
                Toast.makeText(this, "Pilti pole veel tehtud", Toast.LENGTH_SHORT).show();
            }
        });

        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (camera == null) return false;
                float currentZoom = camera.getCameraInfo().getZoomState().getValue() != null
                    ? camera.getCameraInfo().getZoomState().getValue().getZoomRatio() : 1f;
                camera.getCameraControl().setZoomRatio(currentZoom * detector.getScaleFactor());
                return true;
            }
        });

        mPreviewView.setOnTouchListener((v, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_UP && !scaleGestureDetector.isInProgress()) {
                if (camera == null) return true;
                MeteringPoint point = mPreviewView.getMeteringPointFactory().createPoint(event.getX(), event.getY());
                FocusMeteringAction action = new FocusMeteringAction.Builder(point).build();
                camera.getCameraControl().startFocusAndMetering(action);
            }
            return true;
        });

        locationManager = (LocationManager) getSystemService(LocationManager.class);

        if (allPermissionsGranted()) {
            startCameraAndGps();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mainHandler.post(updateTextViewRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(updateTextViewRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        locationManager.removeUpdates(locationListener);
    }

    @SuppressWarnings("MissingPermission")
    private void startCameraAndGps() {
        startCamera();
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 10, locationListener);
    }

    private void loadRoadLocation(double lat, double lon) {
        networkExecutor.execute(() -> {
            try {
                //https://kaine.ee/tram/minimal.php?x=675821.32&y=6466772.75
                String url = "https://kaine.ee/tram/minimal.php?x=" + lat + "&y=" + lon;
                String content = fetchRoadPageContent(url);
                String[] parsed = parseRoadData(content);
                locationOnRoad = parsed[0];
                locationOnRoadSimple = parsed[1];
            } catch (IOException e) {
                mainHandler.post(() ->
                    Toast.makeText(MainActivity.this, "Internet puudub?", Toast.LENGTH_SHORT).show()
                );
            }
        });
    }

    /*
    private String[] parseRoadData(String content) {
        String[] lines = content.split("\\r?\\n");
        List<String> teeHtmlRaw = new ArrayList<>();
        int meetrigaRida = 0;
        boolean meetrigaRidaOlemas = false;
        String kilomeeterPunktiga = "";

        for (int i = 0; i < lines.length; i++) {
            teeHtmlRaw.add(lines[i]);
            if (lines[i].startsWith("Meeter")) {
                meetrigaRida = i;
                meetrigaRidaOlemas = true;
            }
        }

        if (meetrigaRidaOlemas) {
            String[] pooleks = teeHtmlRaw.get(meetrigaRida).split(",");
            String meetritesStr = pooleks[0].replaceAll("[^0-9]+", "");
            if (meetritesStr.matches(".*\\d.*")) {
                float km = Float.parseFloat(meetritesStr) / 1000;
                kilomeeterPunktiga = Float.toString(km).replace('.', ',');
            }
        }

        String roadNumber = teeHtmlRaw.get(0).replaceAll("\\D+", "");
        String roadName = teeHtmlRaw.get(0).replaceAll("\\d", "").replace("Tee  ", "");
        String roadLocation = teeHtmlRaw.get(teeHtmlRaw.size() - 1);

        return new String[]{
            "Tee " + roadNumber + " km " + kilomeeterPunktiga + " " + roadName + " " + roadLocation,
            "Tee " + roadNumber + " km " + kilomeeterPunktiga
        };
    }*/

    private String[] parseRoadData(String content) {
        String[] lines = content.split("\\r?\\n");
        List<String> teeHtmlRaw = new ArrayList<>();
        String infoRida = lines[0];
        /*
        int meetrigaRida = 0;
        boolean meetrigaRidaOlemas = false;
        String kilomeeterPunktiga = "";

        for (int i = 0; i < lines.length; i++) {
            teeHtmlRaw.add(lines[i]);
            if (lines[i].startsWith("Meeter")) {
                meetrigaRida = i;
                meetrigaRidaOlemas = true;
            }
        }

        if (meetrigaRidaOlemas) {
            String[] pooleks = teeHtmlRaw.get(meetrigaRida).split(",");
            String meetritesStr = pooleks[0].replaceAll("[^0-9]+", "");
            if (meetritesStr.matches(".*\\d.*")) {
                float km = Float.parseFloat(meetritesStr) / 1000;
                kilomeeterPunktiga = Float.toString(km).replace('.', ',');
            }
        }

        String roadNumber = teeHtmlRaw.get(0).replaceAll("\\D+", "");
        String roadName = teeHtmlRaw.get(0).replaceAll("\\d", "").replace("Tee  ", "");
        String roadLocation = teeHtmlRaw.get(teeHtmlRaw.size() - 1);

        return new String[]{
                "Tee " + roadNumber + " km " + kilomeeterPunktiga + " " + roadName + " " + roadLocation,
                "Tee " + roadNumber + " km " + kilomeeterPunktiga
        };
        */
        //return infoRida;
        return new String[]{
                infoRida,
                "Tee " + "roadNumber" + " km " + "kilomeeterPunktiga"
        };
    }
    private String fetchRoadPageContent(String url) throws IOException {
        Document doc = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .referrer("https://kaine.ee/tram")
            .get();
        String temp = doc.toString().replace("<br>", "$$$");
        return Jsoup.parse(temp).body().text().replace("$$$", "\n");
    }

    // WGS84 → L-EST97 (EPSG:3301), Lambert Conformal Conic 2SP
    // Input: WGS84 latitude/longitude in decimal degrees
    // Output: [x, y] in L-EST97 metres
    public static double[] wgs84ToLest97(double latDeg, double lonDeg) {
        // GRS80 ellipsoid
        double a  = 6378137.0;
        double f  = 1.0 / 298.257222101;
        double e2 = 2 * f - f * f;
        double e  = Math.sqrt(e2);

        // L-EST97 projection constants
        double phi0    = Math.toRadians(57.0 + 31.0 / 60.0 + 3.194 / 3600.0); // lat of false origin
        double lambda0 = Math.toRadians(24.0);                                  // central meridian
        double phi1    = Math.toRadians(59.0 + 20.0 / 60.0);                   // standard parallel 1
        double phi2    = Math.toRadians(58.0);                                  // standard parallel 2
        double E0      = 500000.0;
        double N0      = 6375000.0;

        double m1 = Math.cos(phi1) / Math.sqrt(1 - e2 * Math.pow(Math.sin(phi1), 2));
        double m2 = Math.cos(phi2) / Math.sqrt(1 - e2 * Math.pow(Math.sin(phi2), 2));
        double t0 = lccT(phi0, e);
        double t1 = lccT(phi1, e);
        double t2 = lccT(phi2, e);

        double n  = (Math.log(m1) - Math.log(m2)) / (Math.log(t1) - Math.log(t2));
        double F  = m1 / (n * Math.pow(t1, n));
        double r0 = a * F * Math.pow(t0, n);

        double phi    = Math.toRadians(latDeg);
        double lambda = Math.toRadians(lonDeg);
        double t      = lccT(phi, e);
        double r      = a * F * Math.pow(t, n);
        double theta  = n * (lambda - lambda0);

        return new double[]{
            E0 + r * Math.sin(theta),
            N0 + r0 - r * Math.cos(theta)
        };
    }

    private static double lccT(double phi, double e) {
        double sinPhi = Math.sin(phi);
        return Math.tan(Math.PI / 4.0 - phi / 2.0) /
            Math.pow((1 - e * sinPhi) / (1 + e * sinPhi), e / 2.0);
    }

    public static double roundCoordinate(double coordinate) {
        return Math.round(coordinate * 100000) / 100000.0;
    }

    private String getDate() {
        return new SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                bindPreview(cameraProviderFuture.get());
            } catch (ExecutionException | InterruptedException e) {
                // unreachable
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindPreview(@NonNull ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();
        CameraSelector cameraSelector = new CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build();
        ImageCapture imageCapture = new ImageCapture.Builder().build();
        preview.setSurfaceProvider(mPreviewView.getSurfaceProvider());
        camera = cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector, preview, imageCapture);

        captureImage.setOnClickListener(v ->
            imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
                @Override
                public void onCaptureSuccess(@NonNull ImageProxy image) {
                    Bitmap bitmap = convertImageProxyToBitmap(image);
                    image.close();
                    writeRoadDataAndSave(bitmap);
                }
            })
        );
    }

    private Bitmap convertImageProxyToBitmap(ImageProxy image) {
        ByteBuffer byteBuffer = image.getPlanes()[0].getBuffer();
        byteBuffer.rewind();
        byte[] bytes = new byte[byteBuffer.capacity()];
        byteBuffer.get(bytes);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    private void writeRoadDataAndSave(Bitmap toEdit) {
        Bitmap dest = Bitmap.createBitmap(toEdit.getWidth(), toEdit.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas cs = new Canvas(dest);
        Paint tPaint = new Paint();
        float textSize = toEdit.getHeight() / 40f;
        tPaint.setTextSize(textSize);
        tPaint.setColor(Color.WHITE);
        tPaint.setStyle(Paint.Style.FILL);
        cs.drawBitmap(toEdit, 0f, 0f, null);
        float lineHeight = tPaint.measureText("yY");

        Paint rectPaint = new Paint();
        rectPaint.setColor(Color.BLACK);
        cs.drawRect(0, 0, toEdit.getWidth(), 2 * lineHeight + 45f, rectPaint);

        String dateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        cs.drawText(locationOnRoad, 20f, lineHeight + 15f, tPaint);
        cs.drawText(String.format(Locale.US, "%.2f / %.2f / %s", myLatitude, myLongitude, dateTime), 20f, lineHeight + 30f + lineHeight, tPaint);

        String filename = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
            + " " + locationOnRoadSimple + ".jpg";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/TeePilt");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return;

            try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                dest.compress(Bitmap.CompressFormat.JPEG, 90, os);
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);
                lastSavedUri = uri;
                mainHandler.post(() ->
                    Toast.makeText(this, "Salvestasin pildi: DCIM/TeePilt/" + filename, Toast.LENGTH_SHORT).show()
                );
            } catch (IOException e) {
                getContentResolver().delete(uri, null, null);
                e.printStackTrace();
            }
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "TeePilt");
            if (!dir.exists()) dir.mkdirs();
            File outputFile = new File(dir, filename);
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                dest.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                lastSavedUri = Uri.fromFile(outputFile);
                mainHandler.post(() ->
                    Toast.makeText(this, "Salvestasin pildi: " + outputFile.getPath(), Toast.LENGTH_SHORT).show()
                );
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCameraAndGps();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
}