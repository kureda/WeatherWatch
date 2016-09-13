/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class WeatherFaceService extends CanvasWatchFaceService {
    public static final String FONT_NAME = "BalooChettan-Regular.ttf";
    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1) / 2;
    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
    public final String LOG_TAG = CanvasWatchFaceService.class.getSimpleName();

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<WeatherFaceService.Engine> mWeakReference;

        public EngineHandler(WeatherFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WeatherFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            DataApi.DataListener {

        public static final String LOW = "low";
        public static final String ICON = "icon";
        public static final String HIGH = "high";
        public static final String SUNSHINE_REQUEST = "/weather-request";
        public static final String SUNSHINE_DATA = "/weather-data";
        public static final String AMBIENT = "_ambient";
        public static final String DRAWABLE = "drawable";
        public static final float COLUMN_HEIGHT = 0.52f;
        private static final String UID = "uuid";
        private static final float HUNDRED = 100.00f;

        final Handler mUpdateTimeHandler = new EngineHandler(this);

        //general
        boolean mIsRound;
        boolean mAmbient;
        //background
        Paint mBackgroundPaint;
        Paint mBackgroundPaintAmbient;
        //icon
        float mXIcon;
        float mYIcon;
        Paint mShadePaint;
        Bitmap mIcon;
        Bitmap mIconAmbient;
        //time text
        float mXTime;
        float mYTime;
        float mTimeTextSize;
        Paint mTimePaint;
        Paint mTimeAmbient;
        //high temperature text
        String mHigh = "";
        float mXHigh;
        float mYHigh;
        float mTemperatureTextSize;
        Paint mHighPaint;
        Paint mTemperatureAmbient;
        //low temperature text
        String mLow = "";
        float mXLow;
        float mYLow;
        Paint mLowPaint;
        //time
        boolean firstHalfSecond;
        Calendar mCalendar;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        boolean mRegisteredTimeZoneReceiver = false;
        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        //Connect to Wearable Api
        GoogleApiClient mClient = new GoogleApiClient.Builder(WeatherFaceService.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();
        private Typeface mTypeface;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WeatherFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setHotwordIndicatorGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL) //OKG up
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(false)
                    .build());

            mTypeface = Typeface.createFromAsset(getApplicationContext().getAssets(), FONT_NAME);

            Context context = getApplicationContext();
            mBackgroundPaint = new Paint();
            int color = ContextCompat.getColor(context, R.color.background);
            mBackgroundPaint.setColor(color);

            mBackgroundPaintAmbient = createPaint(R.color.ambient_background, context, false);
            mTimeAmbient = createPaint(R.color.ambient_text, context, false);
            mTemperatureAmbient = createPaint(R.color.ambient_text, context, false);
            mTimePaint = createPaint(R.color.time_color, context, true);
            mHighPaint = createPaint(R.color.high_color, context, true);
            mLowPaint = createPaint(R.color.low_color, context, true);
            mShadePaint = createPaint(R.color.shade, context, false);

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createPaint(int colorId, Context context, boolean antiAlias) {
            Paint paint = new Paint();
            paint.setColor(ContextCompat.getColor(context, colorId));
            paint.setTypeface(mTypeface);
            paint.setAntiAlias(antiAlias);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
                if (mClient != null && mClient.isConnected()) {
                    Wearable.DataApi.removeListener(mClient, this);
                    mClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WeatherFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WeatherFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
            mIsRound = insets.isRound();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onSurfaceChanged(
                SurfaceHolder holder, int format, int width, int height) {
            calculateElementsCoordinates(width);
            super.onSurfaceChanged(holder, format, width, height);
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            //draw canvas
            canvas.drawPaint(mAmbient ? mBackgroundPaintAmbient : mBackgroundPaint);

            //draw icon
            if (mIcon != null) {
                canvas.drawBitmap(mAmbient ? mIconAmbient : mIcon, mXIcon, mYIcon, null);
            }

            //draw time
            int hr = mCalendar.get(Calendar.HOUR_OF_DAY);
            int min = mCalendar.get(Calendar.MINUTE);
            String time = String.format("%d:%02d", hr, min);
            canvas.drawText(time, mXTime, mYTime, mAmbient ? mTimeAmbient : mTimePaint);
            canvas.drawText(time, mXTime, mYTime, mAmbient ? mTimeAmbient : mTimePaint);
            canvas.drawText(time, mXTime, mYTime, mAmbient ? mTimeAmbient : mTimePaint);

            //blinking column
            firstHalfSecond ^= true;//flip
            if (!isInAmbientMode() && firstHalfSecond) {
                float hoursWidth = mTimePaint.measureText(String.format("%d", hr));
                float columnWidth = mTimePaint.measureText(":");
                float left = mXTime + hoursWidth;
                float right = left + columnWidth;
                float top = mYTime - mTimeTextSize * COLUMN_HEIGHT;
                canvas.drawRect(left, top, right, mYTime + 1, mBackgroundPaint);
            }

            //draw temperature
            canvas.drawText(mHigh, mXHigh, mYHigh, mAmbient ? mTemperatureAmbient : mHighPaint);
            canvas.drawText(mLow, mXLow, mYLow, mAmbient ? mTemperatureAmbient : mLowPaint);

            // set time
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
        }

        private void calculateElementsCoordinates(int width) {
            float w = (float) width;
            Resources res = WeatherFaceService.this.getResources();
            if (mIsRound) {
                mTimeTextSize = scale(R.integer.time_text_size_round, res, w);
                mXIcon = scale(R.integer.x_icon_round, res, w);
                mYIcon = scale(R.integer.y_icon_round, res, w);
                mXTime = scale(R.integer.x_time_round, res, w);
                mYTime = scale(R.integer.y_time_round, res, w);
                mTemperatureTextSize = scale(R.integer.temperature_text_size_round, res, w);
                mXHigh = scale(R.integer.x_high_round, res, w);
                mYHigh = scale(R.integer.y_high_round, res, w);
                mXLow = scale(R.integer.x_low_round, res, w);
                mYLow = scale(R.integer.y_low_round, res, w);
            } else {
                mTimeTextSize = scale(R.integer.time_text_size, res, w);
                mXIcon = scale(R.integer.x_icon, res, w);
                mYIcon = scale(R.integer.y_icon, res, w);
                mXTime = scale(R.integer.x_time, res, w);
                mYTime = scale(R.integer.y_time, res, w);
                mTemperatureTextSize = scale(R.integer.temperature_text_size, res, w);
                mXHigh = scale(R.integer.x_high, res, w);
                mYHigh = scale(R.integer.y_high, res, w);
                mXLow = scale(R.integer.x_low, res, w);
                mYLow = scale(R.integer.y_low, res, w);
            }

            mTimePaint.setTextSize(mTimeTextSize);
            mLowPaint.setTextSize(mTemperatureTextSize);
            mHighPaint.setTextSize(mTemperatureTextSize);
            mTimeAmbient.setTextSize(mTimeTextSize);
            mTemperatureAmbient.setTextSize(mTemperatureTextSize);
        }

        private float scale(int elementId, Resources res, float width) {
            int perecentage = res.getInteger(elementId);
            return perecentage * width / HUNDRED;
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(Bundle connectionHint) {
            Wearable.DataApi.addListener(mClient, this);
            askForWeatherData();
        }

        public void askForWeatherData() {
            PutDataMapRequest mapRequest = PutDataMapRequest.create(SUNSHINE_REQUEST);
            mapRequest.getDataMap().putString(UID, UUID.randomUUID().toString());
            PutDataRequest dataRequest = mapRequest.asPutDataRequest();

            Wearable.DataApi.putDataItem(mClient, dataRequest)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(DataApi.DataItemResult dataItemResult) {
                            if (!dataItemResult.getStatus().isSuccess()) {
                                Log.d(LOG_TAG, "Failed asking phone for weather data");
                            } else {
                                Log.d(LOG_TAG, "Successfully asked for weather data");
                            }
                        }
                    });
        }

        @Override
        public void onConnectionSuspended(int cause) {
            Log.d(LOG_TAG, "onConnectionSuspended: " + cause);
        }

        @Override
        public void onConnectionFailed(ConnectionResult result) {
            Log.d(LOG_TAG, "onConnectionFailed: " + result);
        }


        /**
         * DataApi listener
         */
        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.d(LOG_TAG, "New data received");
            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = event.getDataItem();
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    String path = item.getUri().getPath();
                    if (path.equals(SUNSHINE_DATA)) {
                        mHigh = dataMap.getString(HIGH);
                        mLow = dataMap.getString(LOW);
                        createIcons(dataMap.getString(ICON));
                        invalidate();
                    }
                }
            }
        }

        private void createIcons(String iconName) {
            try {
                String ambientIconName = iconName + AMBIENT;
                Resources res = getResources();
                int id = res.getIdentifier(iconName, DRAWABLE, getPackageName());
                int id2 = res.getIdentifier(ambientIconName, DRAWABLE, getPackageName());
                mIcon = BitmapFactory.decodeResource(res, id);
                mIconAmbient = BitmapFactory.decodeResource(res, id2);
                if (mIsRound) {//icons for round circles must be smaller
                    int width = res.getInteger(R.integer.width_icon_round);
                    int height = res.getInteger(R.integer.height_icon_round);
                    mIcon = Bitmap.createScaledBitmap(mIcon, width, height, true);
                    mIconAmbient = Bitmap.createScaledBitmap(mIconAmbient, width, height, true);
                }
            } catch (Exception ex) {
                mIcon = null;
                mIconAmbient = null;
            }
        }
    }
}
