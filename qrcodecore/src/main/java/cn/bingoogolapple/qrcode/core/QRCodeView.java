package cn.bingoogolapple.qrcode.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.AsyncTask;
import android.os.Handler;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.RelativeLayout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class QRCodeView extends RelativeLayout implements Camera.PreviewCallback {
    protected Camera mCamera;
    protected CameraPreview mCameraPreview;
    protected ScanBoxView mScanBoxView;
    protected Delegate mDelegate;
    protected Handler mHandler;
    protected boolean mSpotAble = false;
    protected int mCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
    private PointF[] mLocationPoints;
    private Paint mPaint;
    protected BarcodeType mBarcodeType = BarcodeType.HIGH_FREQUENCY;
    private static long sLastPreviewFrameTime = 0;
    private static int poolCount = Runtime.getRuntime().availableProcessors();
    private static ExecutorService fixedThreadPool = Executors.newFixedThreadPool(poolCount);
    private long sLastStartTime = 0;

    public QRCodeView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, 0);
    }

    public QRCodeView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mHandler = new Handler();
        initView(context, attrs);
        setupReader();
    }

    private void initView(Context context, AttributeSet attrs) {
        mCameraPreview = new CameraPreview(context);

        mScanBoxView = new ScanBoxView(context);
        mScanBoxView.init(this, attrs);
        mCameraPreview.setId(R.id.bgaqrcode_camera_preview);
        addView(mCameraPreview);
        LayoutParams layoutParams = new LayoutParams(context, attrs);
        layoutParams.addRule(RelativeLayout.ALIGN_TOP, mCameraPreview.getId());
        layoutParams.addRule(RelativeLayout.ALIGN_BOTTOM, mCameraPreview.getId());
        addView(mScanBoxView, layoutParams);

        mPaint = new Paint();
        mPaint.setColor(getScanBoxView().getCornerColor());
        mPaint.setStyle(Paint.Style.FILL);
    }

    protected abstract void setupReader();

    /**
     * 设置扫描二维码的代理
     *
     * @param delegate 扫描二维码的代理
     */
    public void setDelegate(Delegate delegate) {
        mDelegate = delegate;
    }

    public CameraPreview getCameraPreview() {
        return mCameraPreview;
    }

//    /**
//     * 自动对焦成功后，再次对焦的延迟
//     */
//    public void setAutoFocusSuccessDelay(long autoFocusSuccessDelay) {
//        mCameraPreview.setAutoFocusSuccessDelay(autoFocusSuccessDelay);
//    }
//
//    /**
//     * 自动对焦失败后，再次对焦的延迟
//     */
//    public void setAutoFocusFailureDelay(long autoFocusFailureDelay) {
//        mCameraPreview.setAutoFocusSuccessDelay(autoFocusFailureDelay);
//    }

    public ScanBoxView getScanBoxView() {
        return mScanBoxView;
    }

    /**
     * 显示扫描框
     */
    public void showScanRect() {
        if (mScanBoxView != null) {
            mScanBoxView.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 隐藏扫描框
     */
    public void hiddenScanRect() {
        if (mScanBoxView != null) {
            mScanBoxView.setVisibility(View.GONE);
        }
    }

    /**
     * 打开后置摄像头开始预览，但是并未开始识别
     */
    public void startCamera() {
        startCamera(mCameraId);
    }

    /**
     * 打开指定摄像头开始预览，但是并未开始识别
     */
    public void startCamera(int cameraFacing) {
        if (mCamera != null) {
            return;
        }
        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        for (int cameraId = 0; cameraId < Camera.getNumberOfCameras(); cameraId++) {
            Camera.getCameraInfo(cameraId, cameraInfo);
            if (cameraInfo.facing == cameraFacing) {
                startCameraById(cameraId);
                break;
            }
        }
    }

    private void startCameraById(int cameraId) {
        try {
            mCameraId = cameraId;
            mCamera = Camera.open(cameraId);
            mCameraPreview.setCamera(mCamera);
        } catch (Exception e) {
            e.printStackTrace();
            if (mDelegate != null) {
                mDelegate.onScanQRCodeOpenCameraError();
            }
        }
    }

    /**
     * 关闭摄像头预览，并且隐藏扫描框
     */
    public void stopCamera() {
        try {
            stopSpotAndHiddenRect();
            if (mCamera != null) {
                mCameraPreview.stopCameraPreview();
                mCameraPreview.setCamera(null);
                mCamera.release();
                mCamera = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 延迟0.5秒后开始识别
     */
    public void startSpot() {
        startSpotDelay(500);
    }

    /**
     * 延迟delay毫秒后开始识别
     */
    public void startSpotDelay(int delay) {
        mSpotAble = true;

        startCamera();
        // 开始前先移除之前的任务
        if (mHandler != null) {
            mHandler.removeCallbacks(mOneShotPreviewCallbackTask);
            mHandler.postDelayed(mOneShotPreviewCallbackTask, delay);
        }
    }

    /**
     * 停止识别
     */
    public void stopSpot() {
        mSpotAble = false;

//        if (mProcessDataTask != null) {
//            mProcessDataTask.cancelTask();
//            mProcessDataTask = null;
//        }

        if (mCamera != null) {
            try {
                mCamera.setOneShotPreviewCallback(null);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (mHandler != null) {
            mHandler.removeCallbacks(mOneShotPreviewCallbackTask);
        }
    }

    /**
     * 停止识别，并且隐藏扫描框
     */
    public void stopSpotAndHiddenRect() {
        stopSpot();
        hiddenScanRect();
    }

    /**
     * 显示扫描框，并且延迟0.5秒后开始识别
     */
    public void startSpotAndShowRect() {
        startSpot();
        showScanRect();
    }

    /**
     * 打开闪光灯
     */
    public void openFlashlight() {
        mCameraPreview.openFlashlight();
    }

    /**
     * 关闭闪光灯
     */
    public void closeFlashlight() {
        mCameraPreview.closeFlashlight();
    }

    /**
     * 销毁二维码扫描控件
     */
    public void onDestroy() {
        stopCamera();
        mHandler = null;
        mDelegate = null;
        mOneShotPreviewCallbackTask = null;
    }

    /**
     * 切换成扫描条码样式
     */
    public void changeToScanBarcodeStyle() {
        if (!mScanBoxView.getIsBarcode()) {
            mScanBoxView.setIsBarcode(true);
        }
    }

    /**
     * 切换成扫描二维码样式
     */
    public void changeToScanQRCodeStyle() {
        if (mScanBoxView.getIsBarcode()) {
            mScanBoxView.setIsBarcode(false);
        }
    }

    /**
     * 当前是否为条码扫描样式
     */
    public boolean getIsScanBarcodeStyle() {
        return mScanBoxView.getIsBarcode();
    }

    @Override
    public void onPreviewFrame(final byte[] data, final Camera camera) {
        if (BGAQRCodeUtil.isDebug()) {
            BGAQRCodeUtil.d("两次 onPreviewFrame 时间间隔：" + (System.currentTimeMillis() - sLastPreviewFrameTime));
            sLastPreviewFrameTime = System.currentTimeMillis();
        }

//        if (!mSpotAble || (mProcessDataTask != null && (mProcessDataTask.getStatus() == AsyncTask.Status.PENDING
//                || mProcessDataTask.getStatus() == AsyncTask.Status.RUNNING))) {
//            return;
//        }
        fixedThreadPool.execute(new Runnable() {
            @Override
            public void run() {
//                new ProcessDataTask(camera, data, QRCodeView.this, BGAQRCodeUtil.isPortrait(getContext())).perform();

                if (BGAQRCodeUtil.isDebug()) {
                    BGAQRCodeUtil.d("两次任务执行的时间间隔：" + (System.currentTimeMillis() - sLastStartTime));
                    sLastStartTime = System.currentTimeMillis();
                }
                long startTime = System.currentTimeMillis();

                ScanResult scanResult = processData(data, QRCodeView.this);

                if (BGAQRCodeUtil.isDebug()) {
                    long time = System.currentTimeMillis() - startTime;
                    if (scanResult != null && !TextUtils.isEmpty(scanResult.result)) {
                        BGAQRCodeUtil.d("识别成功时间为：" + time);
                    } else {
                        BGAQRCodeUtil.e("识别失败时间为：" + time);
                    }
                }


                onPostParseData(scanResult);
            }

            private ScanResult processData(byte[] mData, QRCodeView qrCodeView) {
                int width = 0;
                int height = 0;
                byte[] data = mData;
                try {
                    Camera.Parameters parameters = mCamera.getParameters();
                    Camera.Size size = parameters.getPreviewSize();
                    width = size.width;
                    height = size.height;
                    if (BGAQRCodeUtil.isPortrait(getContext())) {
                        data = new byte[mData.length];
                        for (int y = 0; y < height; y++) {
                            for (int x = 0; x < width; x++) {
                                data[x * height + height - y - 1] = mData[x + y * width];
                            }
                        }
                        int tmp = width;
                        width = height;
                        height = tmp;
                    }

                    return qrCodeView.processData(data, width, height, false);
                } catch (Exception e1) {
                    e1.printStackTrace();
                    try {
                        if (width != 0 && height != 0) {
                            BGAQRCodeUtil.d("识别失败重试");
                            return qrCodeView.processData(data, width, height, true);
                        } else {
                            return null;
                        }
                    } catch (Exception e2) {
                        e2.printStackTrace();
                        return null;
                    }
                }
            }
        });

    }

    /**
     * 解析本地图片二维码。返回二维码图片里的内容 或 null
     *
     * @param picturePath 要解析的二维码图片本地路径
     */
    public void decodeQRCode(final String picturePath) {
        new ProcessDataTask(picturePath, QRCodeView.this).perform();
    }

    /**
     * 解析 Bitmap 二维码。返回二维码图片里的内容 或 null
     *
     * @param bitmap 要解析的二维码图片
     */
    public void decodeQRCode(Bitmap bitmap) {
        new ProcessDataTask(bitmap, this).perform();
    }

    protected abstract ScanResult processData(byte[] data, int width, int height, boolean isRetry);

    protected abstract ScanResult processBitmapData(Bitmap bitmap);

    void onPostParseData(ScanResult scanResult) {
        if (!mSpotAble) {
            return;
        }
        String result = scanResult == null ? null : scanResult.result;
        if (TextUtils.isEmpty(result)) {
            try {
                if (mCamera != null) {
                    mCamera.setOneShotPreviewCallback(QRCodeView.this);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            try {
                if (mDelegate != null) {
                    mDelegate.onScanQRCodeSuccess(result);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    void onPostParseBitmapOrPicture(ScanResult scanResult) {
        if (mDelegate != null) {
            String result = scanResult == null ? null : scanResult.result;
            mDelegate.onScanQRCodeSuccess(result);
        }
    }

    private Runnable mOneShotPreviewCallbackTask = new Runnable() {
        @Override
        public void run() {
            if (mCamera != null && mSpotAble) {
                try {
                    mCamera.setOneShotPreviewCallback(QRCodeView.this);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    };

//    /**
//     * 计算扫码区域
//     *
//     * @param scanBox     扫码框
//     * @param coefficient 比率
//     */
//    private Rect calculateFocusArea(Rect scanBox, float coefficient, Camera.Size previewSize) {
//        int width = (int) (scanBox.width() * coefficient);
//        int height = (int) (scanBox.height() * coefficient);
//        float scanCenterX = scanBox.centerY();
//        float scanCenterY = scanBox.centerX();
//        int centerX = (int) (scanCenterX / previewSize.width * 2000 - 1000);
//        int centerY = (int) (scanCenterY / previewSize.height * 2000 - 1000);
//        int left = clamp(centerX - (height / 2), -1000, 1000);
//        int top = clamp(centerY - (width / 2), -1000, 1000);
//        RectF rectF = new RectF(left, top, left + height, top + width);
//        return new Rect(Math.round(rectF.left), Math.round(rectF.top),
//                Math.round(rectF.right), Math.round(rectF.bottom));
//    }
//
//    private int clamp(int x, int min, int max) {
//        return Math.min(Math.max(x, min), max);
//    }

//    private Rect mFocusRect;
//    private Rect mMeteringRect;
//    private Paint mPaint = new Paint();
//
//    @Override
//    protected void dispatchDraw(Canvas canvas) {
//        super.dispatchDraw(canvas);
//        mPaint.setStrokeWidth(2);
//        mPaint.setStyle(Paint.Style.STROKE);
//
//        if (mFocusRect != null) {
//            mPaint.setColor(Color.RED);
//            canvas.drawRect(mFocusRect, mPaint);
//        }
//        if (mMeteringRect != null) {
//            mPaint.setColor(Color.GREEN);
//            canvas.drawRect(mMeteringRect, mPaint);
//        }
//    }

    void onScanBoxRectChanged(Rect rect) {
//        if (mCamera != null && rect.left > 0 && rect.top > 0) {
//            try {
//                final Camera.Parameters parameters = mCamera.getParameters();
//                if (parameters.getMaxNumFocusAreas() > 0) {
//                    List<Camera.Area> focusAreas = new ArrayList<>();
//                    Rect focusRect = calculateFocusArea(rect, 1f, parameters.getPreviewSize());
//                    mFocusRect = focusRect;
//                    focusAreas.add(new Camera.Area(focusRect, 1000));
//                    parameters.setFocusAreas(focusAreas);
//                }
//                if (parameters.getMaxNumMeteringAreas() > 0) {
//                    List<Camera.Area> meteringAreas = new ArrayList<>();
//                    Rect meteringRect = calculateFocusArea(rect, 1.5f, parameters.getPreviewSize());
//                    mMeteringRect = meteringRect;
//                    meteringAreas.add(new Camera.Area(meteringRect, 1000));
//                    parameters.setMeteringAreas(meteringAreas);
//                }
//                if (mHandler != null) {
//                    mHandler.postDelayed(new Runnable() {
//                        @Override
//                        public void run() {
//                            if (mCamera != null) {
//                                mCamera.setParameters(parameters);
//                            }
//                        }
//                    }, 500);
//                }
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);


        if (!isShowLocationPoint() || mLocationPoints == null) {
            return;
        }

        for (PointF pointF : mLocationPoints) {
            canvas.drawCircle(pointF.x, pointF.y, 10, mPaint);
        }
        mLocationPoints = null;
        postInvalidateDelayed(2000);
    }

    /**
     * 是否显示定位点
     */
    protected boolean isShowLocationPoint() {
        return mScanBoxView != null && mScanBoxView.isShowLocationPoint();
    }

    protected void transformToViewCoordinates(final PointF[] pointArr, final Rect scanBoxAreaRect) {
        if (pointArr == null || pointArr.length == 0) {
            return;
        }

        new Thread() {
            @Override
            public void run() {
                try {
                    // 不管横屏还是竖屏，size.width 大于 size.height
                    Camera.Size size = mCamera.getParameters().getPreviewSize();
                    boolean isMirrorPreview = mCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT;
                    int statusBarHeight = BGAQRCodeUtil.getStatusBarHeight(getContext());

                    PointF[] transformedPoints = new PointF[pointArr.length];
                    int index = 0;
                    for (PointF qrPoint : pointArr) {
                        transformedPoints[index] = transform(qrPoint.x, qrPoint.y, size.width, size.height, isMirrorPreview, statusBarHeight, scanBoxAreaRect);
                        index++;
                    }
                    mLocationPoints = transformedPoints;
                    postInvalidate();
                } catch (Exception e) {
                    mLocationPoints = null;
                    e.printStackTrace();
                }
            }
        }.start();
    }

    private PointF transform(float originX, float originY, float cameraPreviewWidth, float cameraPreviewHeight, boolean isMirrorPreview, int statusBarHeight,
                             final Rect scanBoxAreaRect) {
        int viewWidth = getWidth();
        int viewHeight = getHeight();

        PointF result;
        float scaleX;
        float scaleY;

        if (BGAQRCodeUtil.isPortrait(getContext())) {
            scaleX = viewWidth / cameraPreviewHeight;
            scaleY = viewHeight / cameraPreviewWidth;
            result = new PointF((cameraPreviewHeight - originX) * scaleX, (cameraPreviewWidth - originY) * scaleY);
            result.y = viewHeight - result.y;
            result.x = viewWidth - result.x;

            if (scanBoxAreaRect == null) {
                result.y += statusBarHeight;
            }
        } else {
            scaleX = viewWidth / cameraPreviewWidth;
            scaleY = viewHeight / cameraPreviewHeight;
            result = new PointF(originX * scaleX, originY * scaleY);
            if (isMirrorPreview) {
                result.x = viewWidth - result.x;
            }
        }

        if (scanBoxAreaRect != null) {
            result.y += scanBoxAreaRect.top;
            result.x += scanBoxAreaRect.left;
        }

        return result;
    }

    public void setType(BarcodeType barcodeType, Object formatList) {
    }

    public interface Delegate {
        /**
         * 处理扫描结果
         *
         * @param result 摄像头扫码时只要回调了该方法 result 就一定有值，不会为 null。解析本地图片或 Bitmap 时 result 可能为 null
         */
        void onScanQRCodeSuccess(String result);

        /**
         * 处理打开相机出错
         */
        void onScanQRCodeOpenCameraError();
    }
}