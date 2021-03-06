package info.nightscout.androidaps.watchfaces;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.support.wearable.view.WatchViewStub;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.google.android.gms.wearable.DataMap;
import com.ustwo.clockwise.common.WatchMode;
import com.ustwo.clockwise.wearable.WatchFace;
import com.ustwo.clockwise.common.WatchFaceTime;
import com.ustwo.clockwise.common.WatchShape;

import java.util.ArrayList;
import java.util.Date;

import info.nightscout.androidaps.data.BasalWatchData;
import info.nightscout.androidaps.data.BgWatchData;
import info.nightscout.androidaps.data.ListenerService;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.TempWatchData;
import lecho.lib.hellocharts.view.LineChartView;

/**
 * Created by emmablack on 12/29/14.
 */
public  abstract class BaseWatchFace extends WatchFace implements SharedPreferences.OnSharedPreferenceChangeListener {
    public final static IntentFilter INTENT_FILTER;
    public static final long[] vibratePattern = {0,400,300,400,300,400};
    public TextView mTime, mSgv, mDirection, mTimestamp, mUploaderBattery, mDelta, mStatus;
    public RelativeLayout mRelativeLayout;
    public LinearLayout mLinearLayout;
    public long sgvLevel = 0;
    public int batteryLevel = 1;
    public int ageLevel = 1;
    public int highColor = Color.YELLOW;
    public int lowColor = Color.RED;
    public int midColor = Color.WHITE;
    public int gridColor = Color.WHITE;
    public int basalBackgroundColor = Color.BLUE;
    public int basalCenterColor = Color.BLUE;
    public boolean lowResMode = false;
    public int pointSize = 2;
    public boolean layoutSet = false;
    public int missed_readings_alert_id = 818;
    public BgGraphBuilder bgGraphBuilder;
    public LineChartView chart;
    public double datetime;
    public ArrayList<BgWatchData> bgDataList = new ArrayList<>();
    public ArrayList<TempWatchData> tempWatchDataList = new ArrayList<>();
    public ArrayList<BasalWatchData> basalWatchDataList = new ArrayList<>();
    public PowerManager.WakeLock wakeLock;
    // related endTime manual layout
    public View layoutView;
    private final Point displaySize = new Point();
    private int specW, specH;

    private LocalBroadcastManager localBroadcastManager;
    private MessageReceiver messageReceiver;

    protected SharedPreferences sharedPrefs;
    private String batteryString = "--";
    private String sgvString = "--";
    private String externalStatusString = "no status";
    private String avgDelta = "";
    private String delta = "";


    @Override
    public void onCreate() {
        super.onCreate();
        Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay();
        display.getSize(displaySize);
        wakeLock = ((PowerManager) getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Clock");

        specW = View.MeasureSpec.makeMeasureSpec(displaySize.x,
                View.MeasureSpec.EXACTLY);
        specH = View.MeasureSpec.makeMeasureSpec(displaySize.y,
                View.MeasureSpec.EXACTLY);
        sharedPrefs = PreferenceManager
                .getDefaultSharedPreferences(this);
        sharedPrefs.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onLayout(WatchShape shape, Rect screenBounds, WindowInsets screenInsets) {
        super.onLayout(shape, screenBounds, screenInsets);
        layoutView.onApplyWindowInsets(screenInsets);
    }

    public void performViewSetup() {
        final WatchViewStub stub = (WatchViewStub) layoutView.findViewById(R.id.watch_view_stub);
        IntentFilter messageFilter = new IntentFilter(Intent.ACTION_SEND);

        messageReceiver = new MessageReceiver();
        localBroadcastManager = LocalBroadcastManager.getInstance(this);
        localBroadcastManager.registerReceiver(messageReceiver, messageFilter);

        stub.setOnLayoutInflatedListener(new WatchViewStub.OnLayoutInflatedListener() {
            @Override
            public void onLayoutInflated(WatchViewStub stub) {
                mTime = (TextView) stub.findViewById(R.id.watch_time);
                mSgv = (TextView) stub.findViewById(R.id.sgv);
                mDirection = (TextView) stub.findViewById(R.id.direction);
                mTimestamp = (TextView) stub.findViewById(R.id.timestamp);
                mStatus = (TextView) stub.findViewById(R.id.externaltstatus);
                mUploaderBattery = (TextView) stub.findViewById(R.id.uploader_battery);
                mDelta = (TextView) stub.findViewById(R.id.delta);
                mRelativeLayout = (RelativeLayout) stub.findViewById(R.id.main_layout);
                mLinearLayout = (LinearLayout) stub.findViewById(R.id.secondary_layout);
                chart = (LineChartView) stub.findViewById(R.id.chart);
                layoutSet = true;
                showAgoRawBattStatus();
                mRelativeLayout.measure(specW, specH);
                mRelativeLayout.layout(0, 0, mRelativeLayout.getMeasuredWidth(),
                        mRelativeLayout.getMeasuredHeight());
            }
        });
        ListenerService.requestData(this);
        wakeLock.acquire(50);
    }

    public int ageLevel() {
        if(timeSince() <= (1000 * 60 * 12)) {
            return 1;
        } else {
            return 0;
        }
    }

    public double timeSince() {
        return System.currentTimeMillis() - datetime;
    }

    public String readingAge(boolean shortString) {
        if (datetime == 0) { return shortString?"--'":"-- Minute ago"; }
        int minutesAgo = (int) Math.floor(timeSince()/(1000*60));
        if (minutesAgo == 1) {
            return minutesAgo + (shortString?"'":" Minute ago");
        }
        return minutesAgo + (shortString?"'":" Minutes ago");
    }

    @Override
    public void onDestroy() {
        if(localBroadcastManager != null && messageReceiver != null){
            localBroadcastManager.unregisterReceiver(messageReceiver);}
        if (sharedPrefs != null){
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(this);
        }
        super.onDestroy();
    }

    static {
        INTENT_FILTER = new IntentFilter();
        INTENT_FILTER.addAction(Intent.ACTION_TIME_TICK);
        INTENT_FILTER.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        INTENT_FILTER.addAction(Intent.ACTION_TIME_CHANGED);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if(layoutSet) {
            this.mRelativeLayout.draw(canvas);
            Log.d("onDraw", "draw");
        }
    }

    @Override
    protected void onTimeChanged(WatchFaceTime oldTime, WatchFaceTime newTime) {
        if (layoutSet && (newTime.hasHourChanged(oldTime) || newTime.hasMinuteChanged(oldTime))) {
            wakeLock.acquire(50);
            final java.text.DateFormat timeFormat = DateFormat.getTimeFormat(BaseWatchFace.this);
            mTime.setText(timeFormat.format(System.currentTimeMillis()));
            showAgoRawBattStatus();

            if(ageLevel()<=0) {
                mSgv.setPaintFlags(mSgv.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            } else {
                mSgv.setPaintFlags(mSgv.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            }

            missedReadingAlert();
            mRelativeLayout.measure(specW, specH);
            mRelativeLayout.layout(0, 0, mRelativeLayout.getMeasuredWidth(),
                    mRelativeLayout.getMeasuredHeight());
        }
    }

    public class MessageReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {

            //data
            Bundle bundle = intent.getBundleExtra("data");
            if (layoutSet && bundle != null) {
                DataMap dataMap = DataMap.fromBundle(bundle);
                wakeLock.acquire(50);
                sgvLevel = dataMap.getLong("sgvLevel");
                batteryLevel = dataMap.getInt("batteryLevel");
                datetime = dataMap.getDouble("timestamp");
                sgvString = dataMap.getString("sgvString");
                batteryString = dataMap.getString("battery");
                mSgv.setText(dataMap.getString("sgvString"));

                if(ageLevel()<=0) {
                    mSgv.setPaintFlags(mSgv.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                } else {
                    mSgv.setPaintFlags(mSgv.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
                }

                final java.text.DateFormat timeFormat = DateFormat.getTimeFormat(BaseWatchFace.this);
                mTime.setText(timeFormat.format(System.currentTimeMillis()));

                mDirection.setText(dataMap.getString("slopeArrow"));
                avgDelta = dataMap.getString("avgDelta");
                delta = dataMap.getString("delta");


                showAgoRawBattStatus();


                if (chart != null) {
                    addToWatchSet(dataMap);
                    setupCharts();
                }
                mRelativeLayout.measure(specW, specH);
                mRelativeLayout.layout(0, 0, mRelativeLayout.getMeasuredWidth(),
                        mRelativeLayout.getMeasuredHeight());
                invalidate();
                setColor();
            }
            //status
            bundle = intent.getBundleExtra("status");
            if (layoutSet && bundle != null) {
                DataMap dataMap = DataMap.fromBundle(bundle);
                wakeLock.acquire(50);
                externalStatusString = dataMap.getString("externalStatusString");

                showAgoRawBattStatus();

                mRelativeLayout.measure(specW, specH);
                mRelativeLayout.layout(0, 0, mRelativeLayout.getMeasuredWidth(),
                        mRelativeLayout.getMeasuredHeight());
                invalidate();
                setColor();
            }
            //basals and temps
            bundle = intent.getBundleExtra("basals");
            if (layoutSet && bundle != null) {
                DataMap dataMap = DataMap.fromBundle(bundle);
                wakeLock.acquire(500);

                loadBasalsAndTemps(dataMap);

                mRelativeLayout.measure(specW, specH);
                mRelativeLayout.layout(0, 0, mRelativeLayout.getMeasuredWidth(),
                        mRelativeLayout.getMeasuredHeight());
                invalidate();
                setColor();
            }
        }
    }

    private void showAgoRawBattStatus() {


        boolean showAvgDelta = sharedPrefs.getBoolean("showAvgDelta", true);
        mDelta.setText(delta);
        if(showAvgDelta){
            mDelta.append("  " + avgDelta);
        }


        if( mTimestamp == null || mUploaderBattery == null|| mStatus == null){
            return;
        }

        boolean showStatus = sharedPrefs.getBoolean("showExternalStatus", true);

        if(showStatus){
            //use short forms
            mTimestamp.setText(readingAge(true));
            mUploaderBattery.setText("U: " + batteryString + "%");
        } else {
            mTimestamp.setText(readingAge(false));
            mUploaderBattery.setText("Uploader: " + batteryString + "%");
        }


        if (showStatus) {
            mStatus.setVisibility(View.VISIBLE);
            mStatus.setText("S: " + externalStatusString);
        } else {
            mStatus.setVisibility(View.GONE);
        }
    }

    public void setColor() {
        if(lowResMode){
            setColorLowRes();
        } else if (sharedPrefs.getBoolean("dark", true)) {
            setColorDark();
        } else {
            setColorBright();
        }
    }

    protected void onWatchModeChanged(WatchMode watchMode) {

        if(lowResMode ^ isLowRes(watchMode)){ //if there was a change in lowResMode
            lowResMode = isLowRes(watchMode);
            setColor();
        } else if (! sharedPrefs.getBoolean("dark", true)){
            //in bright mode: different colours if active:
            setColor();
        }
    }

    private boolean isLowRes(WatchMode watchMode) {
        return (watchMode == WatchMode.LOW_BIT) || (watchMode == WatchMode.LOW_BIT_BURN_IN) || (watchMode == WatchMode.LOW_BIT_BURN_IN);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key){
        setColor();
        if(layoutSet){
            showAgoRawBattStatus();
            mRelativeLayout.measure(specW, specH);
            mRelativeLayout.layout(0, 0, mRelativeLayout.getMeasuredWidth(),
                    mRelativeLayout.getMeasuredHeight());
        }
        invalidate();
    }
protected abstract void setColorDark();
    protected abstract void setColorBright();
    protected abstract void setColorLowRes();


    public void missedReadingAlert() {
        int minutes_since   = (int) Math.floor(timeSince()/(1000*60));
        if(minutes_since >= 16 && ((minutes_since - 16) % 5) == 0) {
            ListenerService.requestData(this); // attempt endTime recover missing data
        }
    }

    public void addToWatchSet(DataMap dataMap) {

        ArrayList<DataMap> entries = dataMap.getDataMapArrayList("entries");
        if (entries != null) {
            bgDataList = new ArrayList<BgWatchData>();
            for (DataMap entry : entries) {
                double sgv = entry.getDouble("sgvDouble");
                double high = entry.getDouble("high");
                double low = entry.getDouble("low");
                double timestamp = entry.getDouble("timestamp");
                bgDataList.add(new BgWatchData(sgv, high, low, timestamp));
            }
        } else {
            double sgv = dataMap.getDouble("sgvDouble");
            double high = dataMap.getDouble("high");
            double low = dataMap.getDouble("low");
            double timestamp = dataMap.getDouble("timestamp");

            final int size = bgDataList.size();
            if (size > 0) {
                if (bgDataList.get(size - 1).timestamp == timestamp)
                    return; // Ignore duplicates.
            }

            bgDataList.add(new BgWatchData(sgv, high, low, timestamp));
        }

        for (int i = 0; i < bgDataList.size(); i++) {
            if (bgDataList.get(i).timestamp < (System.currentTimeMillis() - (1000 * 60 * 60 * 5))) {
                bgDataList.remove(i); //Get rid of anything more than 5 hours old
                break;
            }
        }
    }

    public void setupCharts() {
        if(bgDataList.size() > 0) { //Dont crash things just because we dont have values, people dont like crashy things
            int timeframe = Integer.parseInt(sharedPrefs.getString("chart_timeframe", "3"));
            if (lowResMode) {
                bgGraphBuilder = new BgGraphBuilder(getApplicationContext(), bgDataList, tempWatchDataList, basalWatchDataList, pointSize, midColor, gridColor, basalBackgroundColor, basalCenterColor, timeframe);
            } else {
                bgGraphBuilder = new BgGraphBuilder(getApplicationContext(), bgDataList, tempWatchDataList, basalWatchDataList, pointSize, highColor, lowColor, midColor, gridColor, basalBackgroundColor, basalCenterColor, timeframe);
            }

            chart.setLineChartData(bgGraphBuilder.lineData());
            chart.setViewportCalculationEnabled(true);
            chart.setMaximumViewport(chart.getMaximumViewport());
        } else {
            ListenerService.requestData(this);
        }
    }

    private void loadBasalsAndTemps(DataMap dataMap) {
        ArrayList<DataMap> temps = dataMap.getDataMapArrayList("temps");
        if (temps != null) {
            tempWatchDataList = new ArrayList<>();
            for (DataMap temp : temps) {
                TempWatchData twd = new TempWatchData();
                twd.startTime = temp.getLong("starttime");
                twd.startBasal =  temp.getDouble("startBasal");
                twd.endTime = temp.getLong("endtime");
                twd.endBasal = temp.getDouble("endbasal");
                twd.amount = temp.getDouble("amount");
                tempWatchDataList.add(twd);
            }
        }
        ArrayList<DataMap> basals = dataMap.getDataMapArrayList("basals");
        if (basals != null) {
            basalWatchDataList = new ArrayList<>();
            for (DataMap basal : basals) {
                BasalWatchData bwd = new BasalWatchData();
                bwd.startTime = basal.getLong("starttime");
                bwd.endTime = basal.getLong("endtime");
                bwd.amount = basal.getDouble("amount");
                basalWatchDataList.add(bwd);
            }
        }
    }
}
