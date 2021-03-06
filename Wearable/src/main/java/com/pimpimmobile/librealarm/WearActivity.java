package com.pimpimmobile.librealarm;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.NfcManager;
import android.nfc.Tag;
import android.nfc.tech.NfcV;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Vibrator;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.MotionEvent;
import android.view.WindowManager;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.gson.Gson;
import com.pimpimmobile.librealarm.shareddata.AlertRules;
import com.pimpimmobile.librealarm.shareddata.AlertRules.Danger;
import com.pimpimmobile.librealarm.shareddata.AlgorithmUtil;
import com.pimpimmobile.librealarm.shareddata.PredictionData;
import com.pimpimmobile.librealarm.shareddata.PreferencesUtil;
import com.pimpimmobile.librealarm.shareddata.ReadingData;
import com.pimpimmobile.librealarm.shareddata.Status;
import com.pimpimmobile.librealarm.shareddata.Status.Type;
import com.pimpimmobile.librealarm.shareddata.WearableApi;

import java.io.IOException;
import java.util.Arrays;

public class WearActivity extends Activity implements ConnectionCallbacks,
        OnConnectionFailedListener, MessageApi.MessageListener {

    private static final String TAG = "GLUCOSE::" + WearActivity.class.getSimpleName();

    public static final String EXTRA_CANCEL_ALARM = "cancel_alarm";

    public static final int MAX_ATTEMPTS = 5;

    private GoogleApiClient mGoogleApiClient;

    private NfcAdapter mNfcAdapter;

    private PowerManager.WakeLock mWakeLock;

    private Handler mHandler = new Handler();

    private Vibrator mVibrator;

    // We can't finish activity until all messages have been sent.
    private int mMessagesBeingSent;
    private boolean mFinishAfterSentMessages = false;

    private ResultCallback<MessageApi.SendMessageResult> mMessageListener =
            new ResultCallback<MessageApi.SendMessageResult>() {
        @Override
        public void onResult(@NonNull MessageApi.SendMessageResult sendMessageResult) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "messagesbeingsent: " + mMessagesBeingSent +", finish? " + mFinishAfterSentMessages);
                    if (--mMessagesBeingSent <= 0 && mFinishAfterSentMessages) {
                        finish();
                    }
                }
            });
        }
    };

    private Runnable mStopActivityRunnable = new Runnable() {
        @Override
        public void run() {
            int retries = PreferencesUtil.getRetries(WearActivity.this);
            mFinishAfterSentMessages = true;
            if (retries >= MAX_ATTEMPTS) {
                PreferencesUtil.setRetries(WearActivity.this, 1);
                setNextAlarm();
                sendResultAndFinish();
                if (shouldDoErrorAlarm()) {
                    doAlarm(Type.ALARM_OTHER, -1, AlgorithmUtil.TrendArrow.UNKNOWN);
                } else {
                    sendStatusUpdate(Type.WAITING);
                }
            } else {
                sendStatusUpdate(Type.ATTENPT_FAILED);
                PreferencesUtil.setRetries(WearActivity.this, ++retries);
            }
        }
    };

    private boolean shouldDoErrorAlarm() {
        return PreferencesUtil.increaseErrorsInARow(this) >=
                PreferencesUtil.errInRowForAlarm(this);
    }

    private ReadingData mResult = new ReadingData(PredictionData.Result.ERROR_NO_NFC);

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        Log.i(TAG, "onCreate()");
        if (getIntent().hasExtra(EXTRA_CANCEL_ALARM)) {
            finish();
            return;
        }
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN |
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                        WindowManager.LayoutParams.FLAG_FULLSCREEN |
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        setContentView(R.layout.wear_activity);
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();

        // If attempt fails for some reason, retry in 20 seconds.
        if (PreferencesUtil.getIsStarted(this)) AlarmReceiver.post(this, 20000);

        mGoogleApiClient.connect();
        NfcManager nfcManager =
                (NfcManager) WearActivity.this.getSystemService(Context.NFC_SERVICE);
        mNfcAdapter = nfcManager.getDefaultAdapter();
        mNfcAdapter.enableReaderMode(WearActivity.this, new NfcAdapter.ReaderCallback() {
            @Override
            public void onTagDiscovered(Tag tag) {
                new NfcVReaderTask().execute(tag);
                mNfcAdapter.disableReaderMode(WearActivity.this);
            }
        }, NfcAdapter.FLAG_READER_NFC_V, null);

        PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
        mWakeLock = manager.newWakeLock(PowerManager.FULL_WAKE_LOCK |
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "tag");

        mWakeLock.acquire();

        // If NFC reading hasn't been completed within 10 seconds, close the app.
        mHandler.postDelayed(mStopActivityRunnable, 10000);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (intent.hasExtra(EXTRA_CANCEL_ALARM)) {
            sendStatusUpdate(Type.WAITING);
            mFinishAfterSentMessages = true;
        }
        super.onNewIntent(intent);
    }

    @Override
    protected void onStop() {
        Log.i(TAG,"onStop");
        mHandler.removeCallbacksAndMessages(null);
        if (mWakeLock.isHeld()) mWakeLock.release();
        if (mVibrator != null) mVibrator.cancel();
        if (mNfcAdapter != null) mNfcAdapter.disableReaderMode(this);
        if (mGoogleApiClient.isConnected()) {
            Wearable.MessageApi.removeListener(mGoogleApiClient, this);
            mGoogleApiClient.disconnect();
        }
        finish();
        super.onStop();
    }

    private void setNextAlarm() {
        if (PreferencesUtil.getIsStarted(this)) {
            AlarmReceiver.post(this, AlarmReceiver.DEFAULT_INTERVAL);
        }
    }

    @Override
    public void onConnected(Bundle connectionHint) {
        Log.i(TAG, "is connected!!");
        Wearable.MessageApi.addListener(mGoogleApiClient, this);
        sendStatusUpdate(Type.ATTEMPTING);
    }

    private void sendAlarmStatusUpdate(Type type, int value, AlgorithmUtil.TrendArrow trendArrow) {
        int attempt = PreferencesUtil.getRetries(this);
        Status status = new Status(type, attempt, WearActivity.MAX_ATTEMPTS,
                AlarmReceiver.getNextCheck(mGoogleApiClient.getContext()), value, trendArrow);
        sendStatusUpdate(type, status);
    }

    private void sendStatusUpdate(Type type) {
        int attempt = PreferencesUtil.getRetries(this);
        Status status = new Status(type, attempt, WearActivity.MAX_ATTEMPTS,
                AlarmReceiver.getNextCheck(mGoogleApiClient.getContext()));
        sendStatusUpdate(type, status);
    }

    private void sendStatusUpdate(Type type, Status status) {
        PreferencesUtil.setCurrentType(this, type);
        mMessagesBeingSent++;
        WearableApi.sendMessage(mGoogleApiClient, WearableApi.STATUS, new Gson().toJson(status), mMessageListener);
    }

    @Override
    public void onConnectionSuspended(int cause) {
        Log.d(TAG, "onConnectionSuspended(): Connection to Google API client was suspended");
    }

    @Override
    public void onMessageReceived(MessageEvent event) {
        DataLayerListenerService.handleMessage(mGoogleApiClient, event);
    }

    /**
     * Disables touch
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        return true;
    }

    private void doAlarm(Type type, int value, AlgorithmUtil.TrendArrow arrow) {
        mFinishAfterSentMessages = false;
        sendAlarmStatusUpdate(type, value, arrow);
        mVibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        mVibrator.vibrate(30000);
        // Close app after it has vibrated for 30 seconds.
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                mFinishAfterSentMessages = true;
                sendStatusUpdate(Type.WAITING);
            }
        }, 30000);
    }

    private void sendResultAndFinish() {
        SimpleDatabase database = new SimpleDatabase(this);
        long id = database.saveMessage(mResult);
        ReadingData.TransferObject transferObject = new ReadingData.TransferObject(id, mResult);
        database.close();
        WearableApi.sendMessage(mGoogleApiClient, WearableApi.GLUCOSE, new Gson().toJson(transferObject), mMessageListener);
        mMessagesBeingSent++;
        mFinishAfterSentMessages = true;
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        Log.e(TAG, "onConnectionFailed(): Failed to connect, with result: " + connectionResult);
    }

    private class NfcVReaderTask extends AsyncTask<Tag, Void, Tag> {

        private byte[] data = new byte[360];

        @Override
        protected void onPostExecute(Tag tag) {
            if (tag == null) return;
            String tagId = bytesToHexString(tag.getId());
            int attempt = PreferencesUtil.getRetries(WearActivity.this);
            mResult = AlgorithmUtil.parseData(attempt, tagId, data);
            PreferencesUtil.setRetries(WearActivity.this, 1);
            PreferencesUtil.resetErrorsInARow(WearActivity.this);
            sendResultAndFinish();
            setNextAlarm();
            Danger danger = AlertRules.check(WearActivity.this, mResult.prediction);
            if (Danger.NOTHING != danger) {
                mHandler.removeCallbacks(mStopActivityRunnable);
                Type type = danger == Danger.LOW ? Type.ALARM_LOW : Type.ALARM_HIGH;
                doAlarm(type, mResult.prediction.glucoseLevel,
                        AlgorithmUtil.getTrendArrow(mResult.prediction));
            } else {
                sendStatusUpdate(Type.WAITING);
            }
        }

        @Override
        protected Tag doInBackground(Tag... params) {
            Tag tag = params[0];
            NfcV nfcvTag = NfcV.get(tag);
            try {
                nfcvTag.connect();
                final byte[] uid = tag.getId();
                for(int i=0; i <= 40; i++) {
                    byte[] cmd = new byte[]{0x60, 0x20, 0, 0, 0, 0, 0, 0, 0, 0, (byte)i, 0};
                    System.arraycopy(uid, 0, cmd, 2, 8);
                    byte[] oneBlock;
                    Long time = System.currentTimeMillis();
                    while (true) {
                        try {
                            oneBlock = nfcvTag.transceive(cmd);
                            break;
                        } catch (IOException e) {
                            if ((System.currentTimeMillis() > time + 2000)) {
                                Log.e(TAG, "tag read timeout");
                                return null;
                            }
                        }
                    }

                    oneBlock = Arrays.copyOfRange(oneBlock, 2, oneBlock.length);
                    System.arraycopy(oneBlock, 0, data, i*8, 8);
                }

            } catch (Exception e) {
                Log.i(TAG, e.toString());
                return null;
            } finally {
                try {
                    nfcvTag.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing tag!");
                }
            }

            return tag;
        }
    }

    private String bytesToHexString(byte[] src) {
        StringBuilder builder = new StringBuilder("");
        if (src == null || src.length <= 0) {
            return "";
        }

        char[] buffer = new char[2];
        for (byte b : src) {
            buffer[0] = Character.forDigit((b >>> 4) & 0x0F, 16);
            buffer[1] = Character.forDigit(b & 0x0F, 16);
            builder.append(buffer);
        }

        return builder.toString();
    }
}
