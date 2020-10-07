package com.kaarel.teepilt;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.extensions.HdrImageCaptureExtender;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

//TODO
//Thread, mis update'b textviewd jääb taustale tööle. Kuidagi tuleks see ära fixida.

public class MainActivity extends AppCompatActivity {

    private Executor executor = Executors.newSingleThreadExecutor();
    private int REQUEST_CODE_PERMISSIONS = 1001;
    private final String[] REQUIRED_PERMISSIONS = new String[]{"android.permission.CAMERA", "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.ACCESS_FINE_LOCATION"};

    //GPSi leidmiseks vajalik
    private LocationManager locationManager;
    private LocationListener listener;
    //Koordinaadid
    private Double myLatitude = 59.00;
    private Double myLongitude = 27.73;

    private TextView textView;

    public String locationOnRoad="";
    public String locationOnRoadSimple="";

    PreviewView mPreviewView;
    ImageView captureImage;
    ImageView galleryImage;

    int countDown=3;

        @SuppressLint("WrongConstant")
    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //NUPUD:
        mPreviewView = findViewById(R.id.previewView);
        captureImage = findViewById(R.id.captureImg);
        galleryImage = findViewById(R.id.openGallery);

        textView = (TextView) findViewById(R.id.textView);

        ToggleButton toggle = (ToggleButton) findViewById(R.id.toggleButton);

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                myLatitude = roundCoordinate(location.getLatitude());
                myLongitude = roundCoordinate(location.getLongitude());

                WebLoaderAsyncTask getRoadLoc=new WebLoaderAsyncTask(myLatitude,myLongitude);
                //System.out.println("TEST! KAS see ka töötab?");
                //Paneme tööle tee asukoha saamise:
                getRoadLoc.execute();
            }

            @Override
            public void onStatusChanged(String s, int i, Bundle bundle) {

            }

            @Override
            public void onProviderEnabled(String s) {

            }

            @Override
            public void onProviderDisabled(String s) {
                Intent i = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                startActivity(i);
            }
        };


        if (allPermissionsGranted()) {
            startCamera(); //start camera if permission has been granted by user

            //START GPS:
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            locationManager.requestLocationUpdates("gps", 2, 10, listener);

            // See thread uuendab textviews koordinaate 1 seci tagant
            Thread updateTextview = new Thread() {

                @Override
                public void run() {
                    try {
                        while (!isInterrupted()) {
                            Thread.sleep(1000);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    textView.setText(locationOnRoad + "\n" +myLatitude+", "+myLongitude+" "+getDate());
                                    System.out.println("Updating textview:" + locationOnRoad);
                                }
                            });
                        }
                    } catch (InterruptedException e) {
                    }
                }
            };
            updateTextview.start();


        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

            final boolean[] isThreadAllowed = {true};
            toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {

                    Thread autocapture = new Thread() {
                        @Override
                        public void run() {
                            while (isThreadAllowed[0]) { // Exit when thread's interrupt flag is set
                                try {
                                    Thread.sleep(3000);
                                    System.out.println("CAMERA!!!");
                                    shoot();
                                    //bindPreview(captureImage);
                                } catch (InterruptedException ex) {
                                    System.out.println("CAMERA EXIT!!!");
                                     Thread.currentThread().interrupt();
                                }
                            }
                        }
                    };

                    if (isChecked) {
                        // The toggle is enabled
                        toggle.setText("ON");
                        Toast.makeText(MainActivity.this, "Automaatne salvestus algas", Toast.LENGTH_SHORT).show();
                        isThreadAllowed[0] =true;
                        autocapture.start();

                    } else {
                        // The toggle is disabled
                        toggle.setText("OFF");
                        Toast.makeText(MainActivity.this, "Automaatne salvestus lõpes", Toast.LENGTH_SHORT).show();
                        isThreadAllowed[0] =false;
                    }
                }
            });
    }

    //Koordinaatide ümardamine:
    public static double roundCoordinate(double coordinate) {
        double longCoordinate = coordinate * 100000;
        double roundedLongCoordinate = Math.round(longCoordinate);
        double roundedCoordinate = roundedLongCoordinate / 100000;
        return roundedCoordinate;
    }

    //Aeg stringina
    public String getDate() {
        Date cDate = new Date();
        String fDate = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss").format(cDate);
        return fDate;
    }

    //Kaamera käimapanekuks:
    private void startCamera() {

        final ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(new Runnable() {
            @Override
            public void run() {
                try {

                    ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                    bindPreview(cameraProvider);

                } catch (ExecutionException | InterruptedException e) {
                    // No errors need to be handled for this Future.
                    // This should never be reached.
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    void bindPreview(@NonNull ProcessCameraProvider cameraProvider) {

        Preview preview = new Preview.Builder()
                .build();

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .build();

        ImageCapture.Builder builder = new ImageCapture.Builder();

        //Vendor-Extensions (The CameraX extensions dependency in build.gradle)
        HdrImageCaptureExtender hdrImageCaptureExtender = HdrImageCaptureExtender.create(builder);

        // Query if extension is available (optional).
        if (hdrImageCaptureExtender.isExtensionAvailable(cameraSelector)) {
            // Enable the extension if available.
            hdrImageCaptureExtender.enableExtension(cameraSelector);
        }

        final ImageCapture imageCapture = builder
                .setTargetRotation(this.getWindowManager().getDefaultDisplay().getRotation())
                .build();

        //preview.setSurfaceProvider(mPreviewView.createSurfaceProvider());
        preview.setSurfaceProvider(mPreviewView.getSurfaceProvider());

        Camera camera = cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector, preview, imageAnalysis, imageCapture);

        //Kaamera nupu vajutamisel:
        captureImage.setOnClickListener(v -> {

            captureImage.setColorFilter(Color.GRAY, PorterDuff.Mode.MULTIPLY);

            imageCapture.takePicture(executor, new ImageCapture.OnImageCapturedCallback(){
                public void onCaptureSuccess(ImageProxy image){
                    Bitmap bitmap = convertImageProxyToBitmap(image);
                    writeRoadDataAndTimeAndSave(bitmap);
                    captureImage.clearColorFilter();
                    image.close();
                }
            });

        });
    }

    void shoot(){
        new Handler(Looper.getMainLooper()).post(new Runnable(){
            @Override
            public void run() {
                captureImage.performClick();
            }
        });
    }

    //Failiasukoha jaoks:
    public String getBatchDirectoryName() {

        String app_folder_path = "";
        app_folder_path = Environment.getExternalStorageDirectory().toString() + "/TeePilt";
        File dir = new File(app_folder_path);
        if (!dir.exists() && !dir.mkdirs()) {
        }
        return app_folder_path;
    }

    //Convert ImageProxy to Bitmap
    private Bitmap convertImageProxyToBitmap(ImageProxy image) {
        ByteBuffer byteBuffer = image.getPlanes()[0].getBuffer();
        byteBuffer.rewind();
        byte[] bytes = new byte[byteBuffer.capacity()];
        byteBuffer.get(bytes);
        byte[] clonedBytes = bytes.clone();
        return BitmapFactory.decodeByteArray(clonedBytes, 0, clonedBytes.length);
    }

    //TimeStampi panek ja teekilomeeter pildile:
    public Bitmap writeRoadDataAndTimeAndSave(Bitmap toEdit){
        Bitmap dest = Bitmap.createBitmap(toEdit.getWidth(), toEdit.getHeight(), Bitmap.Config.ARGB_8888);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String dateTime = sdf.format(Calendar.getInstance().getTime()); // reading local time in the system

        Canvas cs = new Canvas(dest);
        Paint tPaint = new Paint();

        float textSize=toEdit.getHeight()/40;

        //Kõrguse järgi muudan teksti suurust
        tPaint.setTextSize(textSize);
        tPaint.setColor(Color.WHITE);
        tPaint.setStyle(Paint.Style.FILL);
        cs.drawBitmap(toEdit, 0f, 0f, null);
        float height = tPaint.measureText("yY");

        Paint rectPaint = new Paint();
        rectPaint.setColor(Color.BLACK);
        cs.drawRect(0,0,toEdit.getWidth(),2*height+15f+15f+15f,rectPaint);

        //Pildile teeasukoht, koordinaadid, kuupäev
        cs.drawText(locationOnRoad, 20f, height+15f, tPaint);
        cs.drawText(myLatitude+" / "+myLongitude+" / "+dateTime, 20f, height+15f+15f+height, tPaint);

        try {
            SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
            String kuupaev=mDateFormat.format(new Date());

            String outputFilename=getBatchDirectoryName() + "/IMG_"+kuupaev+" "+locationOnRoadSimple+".jpg";

            //dest.compress(Bitmap.CompressFormat.JPEG, 90, new FileOutputStream(new File(getBatchDirectoryName() + "/"+locationOnRoadSimple+" "+mDateFormat.format(new Date())+".jpg")));
            dest.compress(Bitmap.CompressFormat.JPEG, 90, new FileOutputStream(new File(outputFilename)));
            //Lets write a toast:
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, "Salvestasin pildi: "+outputFilename, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return null;
        }
        return dest;
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

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
                this.finish();
            }
        }
    }

//
//
//WEBLOADER ASYNC TASK
//

    class WebLoaderAsyncTask extends AsyncTask<Void, Void, Void> {
        // Koordinaatide saatmiseks asyntaskile.
        private double latitude;
        private double longitude;
        private String result;

        public WebLoaderAsyncTask(double param1, double param2) {
            this.latitude = param1;
            this.longitude = param2;
        }

        //HTMLi laadimiseks vajalik
        String htmlPageUrl = "https://teed.jairus.ee/teed.php?k=58.312748,26.044717";
        Document htmlDocument;
        //    TextView parsedHtmlNode;
        String htmlContentInStringFormat;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        @Override
        protected Void doInBackground(Void... params) {
            try {
                htmlPageUrl = "https://teed.jairus.ee/teed.php?k="+myLatitude+","+myLongitude;
                //Tõmbame veebilehe Stringiks:
                htmlContentInStringFormat= getWebPageToString(htmlPageUrl);

                //PLAAN
                //Texti hulgast leida meetri rida.
                //Võtta sealt meeter teisendada km-iks.

                //Jupitame stringi vastavalt ridade vahetusele ära
                String lines[] = htmlContentInStringFormat.split("\\r?\\n");

                //Road data jaoks:
                List<String> teeHtmlRaw = new ArrayList<>();

                int meetrigaRida = 0;
                boolean meetrigaRidaOlemas = false;
                String kilomeeterPunktiga = "";

                //Kõik jubinad kokku ühte arraylisti
                for (int i = 0; i < lines.length; i++) {
                    teeHtmlRaw.add(lines[i]);
                    //Otsime meetri lause üles
                    if (teeHtmlRaw.get(i).startsWith("Meeter")) {
                        meetrigaRida = i;
                        meetrigaRidaOlemas = true;
                    }
                }

                //Kui meetriga rida on oleams, siis saab edasi hakkida stringe:
                if (meetrigaRidaOlemas) {
                    //Meetrid->number->kilomeetriteks
                    String pooleks[] = teeHtmlRaw.get(meetrigaRida).split(","); //Meeter 5766, teljest 0m //koma kohalt pooleks
                    String meetritesStr = pooleks[0].replaceAll("[^0-9]+", "");

                    //meetritesStr-is on numbreid, siis teeme kilomeetriteks, kui põllul asume ei tee midagi.
                    if (meetritesStr.matches(".*\\d.*")) {
                        //Stringimeeter Floatiks ja arvutus, et saada km-id
                        float meetritesFloat = Float.parseFloat(meetritesStr);
                        float kilomeeterFloat = meetritesFloat / 1000;
                        //Tagasi stringiks ja punkti vahetus koma vastu.
                        String kilomeeterString = Float.toString(kilomeeterFloat);
                        kilomeeterPunktiga = kilomeeterString.replace('.', ',');
                    }
                }

                //Teenumbri saamiseks kustutame ära kõik, mis pole numbrid
                String roadNumber = teeHtmlRaw.get(0).replaceAll("\\D+","");
                //System.out.println("DEBUG Tee Number:" + roadNumber);
                String roadName=teeHtmlRaw.get(0).replaceAll("\\d","").replace("Tee  ","");
                //System.out.println("DEBUG Tee Number:" + roadName);

                //TeeAsukoht
                String roadLocation=teeHtmlRaw.get(teeHtmlRaw.size() - 1);
                String roadKilometer=kilomeeterPunktiga;
                //System.out.println("DEBUG Tee Number:" + roadKilometer);

                //Valmis Tee nimed:
                locationOnRoad="Tee "+roadNumber+" km "+roadKilometer+" "+roadName+" "+roadLocation;
                locationOnRoadSimple="Tee "+roadNumber+" km "+roadKilometer;

                //textView.setText(locationOnRoad + "\n" +myLatitude+", "+myLongitude+" "+getDate());

            } catch (IOException e) {
                e.printStackTrace();
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "Internet puudub?", Toast.LENGTH_SHORT).show();
                    }
                });
            }
            return null;
        }

        protected void onPostExecute(String result) {
            this.result = result;
            //parsedHtmlNode.setText(htmlContentInStringFormat);
            //System.out.println("DEBUG TEEKILOMEETER:" + htmlContentInStringFormat);
            //Log.d("Teeasukoht: ",htmlContentInStringFormat);
        }

        //Lehe alla laadimiseks:
        protected String getWebPageToString(String WebPage) throws IOException {
            htmlPageUrl=WebPage;

            //Üritame Lehte tõmmata
            htmlDocument = Jsoup.connect(htmlPageUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.169 Safari/537.36")
                    .referrer("https://www.teed.jairus.ee")
                    .get();

            String htmlDocString = htmlDocument.toString();

            //Asendame <br>id mingi muud märgiga
            String temp = htmlDocString.replace("<br>", "$$$");
            Document doc1 = Jsoup.parse(temp);

            //Asendame märgid reavahetusega
            String text = doc1.body().text().replace("$$$", "\n").toString();

            //htmlContentInStringFormat = text;
            return text;
        }
    }
}