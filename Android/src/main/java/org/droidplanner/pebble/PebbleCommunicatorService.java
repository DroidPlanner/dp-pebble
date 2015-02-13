package org.droidplanner.pebble;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.widget.Toast;

import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.util.PebbleDictionary;
import com.o3dr.android.client.Drone;
import com.o3dr.android.client.ControlTower;
import com.o3dr.android.client.interfaces.DroneListener;
import com.o3dr.android.client.interfaces.TowerListener;
import com.o3dr.services.android.lib.drone.attribute.AttributeEvent;
import com.o3dr.services.android.lib.drone.attribute.AttributeType;
import com.o3dr.services.android.lib.drone.connection.ConnectionParameter;
import com.o3dr.services.android.lib.drone.connection.ConnectionResult;
import com.o3dr.services.android.lib.drone.property.Altitude;
import com.o3dr.services.android.lib.drone.property.Battery;
import com.o3dr.services.android.lib.drone.property.GuidedState;
import com.o3dr.services.android.lib.drone.property.Speed;
import com.o3dr.services.android.lib.drone.property.State;
import com.o3dr.services.android.lib.drone.property.VehicleMode;
import com.o3dr.services.android.lib.gcs.event.GCSEvent;
import com.o3dr.services.android.lib.gcs.follow.FollowState;
import com.o3dr.services.android.lib.gcs.follow.FollowType;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class PebbleCommunicatorService extends Service implements DroneListener, TowerListener {
    private static final int KEY_MODE = 0;
    private static final int KEY_FOLLOW_TYPE = 1;
    private static final int KEY_TELEM = 2;
    private static final int KEY_APP_VERSION = 3;

    private static final UUID DP_UUID = UUID.fromString("1de866f1-22fa-4add-ba55-e7722167a3b4");
    private static final String EXPECTED_APP_VERSION = "1";

    private static boolean safeToSendNextPacketToPebble = true;

    public static final String ACTION_CHECK_CONNECTION_STATE = "org.droidplanner.pebble.action.CHECK_CONNECTION_STATE";

    private static final long WATCHDOG_TIMEOUT = 5 * 1000; //ms

    private Context applicationContext;
    private ControlTower controlTower;
    private ConnectionParameter connParams;
    private Drone drone;
    private final Handler handler = new Handler();

    private String lastReceivedAction = null;
    long timeWhenLastTelemSent = System.currentTimeMillis();
    private PebbleKit.PebbleDataReceiver pebbleDataHandler;

    //TODO use this eventFilter
    public final static IntentFilter eventFilter = new IntentFilter();
    static {
        eventFilter.addAction(AttributeEvent.STATE_CONNECTED);
        eventFilter.addAction(AttributeEvent.STATE_VEHICLE_MODE);
        eventFilter.addAction(AttributeEvent.BATTERY_UPDATED);
        eventFilter.addAction(AttributeEvent.SPEED_UPDATED);
        eventFilter.addAction(AttributeEvent.FOLLOW_UPDATE);
    }

    private final Runnable destroyWatchdog = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(this);

            if (drone == null || !drone.isConnected()) {
                stopSelf();
            }

            handler.postDelayed(this, WATCHDOG_TIMEOUT);
        }
    };

    @Override
    public void onCreate(){
        super.onCreate();
        applicationContext = getApplicationContext();
        pebbleDataHandler = new PebbleReceiverHandler(DP_UUID);
        PebbleKit.registerReceivedDataHandler(applicationContext, pebbleDataHandler);
        PebbleKit.registerReceivedAckHandler(applicationContext, new PebbleKit.PebbleAckReceiver(DP_UUID){
            @Override
            public void receiveAck(Context context, int transactionId){//Did pebble receive last msg?
                safeToSendNextPacketToPebble = true;
            }
        });
        PebbleKit.registerReceivedNackHandler(applicationContext, new PebbleKit.PebbleNackReceiver(DP_UUID){
            @Override
            public void receiveNack(Context context, int transactionId){//Did pebble receive last msg?
                safeToSendNextPacketToPebble = true;
            }
        });

        controlTower = new ControlTower(applicationContext);
        this.drone = new Drone();
    }

    //Start the dp-pebble background service
    @Override
    public int onStartCommand(Intent intent, int flags, int startid) {
        if(intent != null) {
            final String action = intent.getAction();
            lastReceivedAction = action;
            switch (action) {
                case GCSEvent.ACTION_VEHICLE_CONNECTION:
                    PebbleKit.startAppOnPebble(applicationContext, DP_UUID);
                    connParams = intent.getParcelableExtra("extra_connection_parameter");
                    connect3DRServices();
                    break;

                case GCSEvent.ACTION_VEHICLE_DISCONNECTION:
                    PebbleKit.closeAppOnPebble(getApplicationContext(), DP_UUID);
                    stopSelf();
                    break;

                case ACTION_CHECK_CONNECTION_STATE:
                    if (!drone.isConnected()) {
                        if(controlTower.isTowerConnected()){
                            checkConnectedApps();
                        }
                        else{
                            connect3DRServices();
                        }
                    }
                    break;

            }
        }

        //Start a watchdog to automatically stop the service when it's no longer needed.
        handler.removeCallbacks(destroyWatchdog);
        handler.postDelayed(destroyWatchdog, WATCHDOG_TIMEOUT);

        //Using redeliver intent because we want the intent to be resend if the service is killed.
        return START_REDELIVER_INTENT;
    }

    @SuppressLint("NewAPI")
    public void connect3DRServices() {
        if(controlTower.isTowerConnected())
            return;

        controlTower.connect(this);

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent resultPendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        notificationIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                );
        final Notification.Builder notificationBuilder = new Notification.Builder(applicationContext).
                setContentTitle("Tower-Pebble Running").
                setSmallIcon(R.drawable.ic_stat_notification).
                setContentIntent(resultPendingIntent);
        final Notification notification = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN
                ? notificationBuilder.build()
                : notificationBuilder.getNotification();
        startForeground(1, notification);
    }

    //Runs when 3dr-services is connected.  Immediately connects to drone.
    @Override
    public void onTowerConnected() {

        if (!drone.isStarted()) {
            controlTower.registerDrone(drone, handler);
            this.drone.registerDroneListener(this);
        }

        switch(lastReceivedAction) {
            case GCSEvent.ACTION_VEHICLE_CONNECTION:
                connectDrone();
                break;

            case ACTION_CHECK_CONNECTION_STATE:
                checkConnectedApps();
                break;
        }
    }

    private void checkConnectedApps(){
        //Check if the Tower app connected behind our back.
        Bundle[] appsInfo = controlTower.getConnectedApps();
        if (appsInfo != null) {
            for (Bundle info : appsInfo) {
                final String appId = info.getString(GCSEvent.EXTRA_APP_ID);
                if (GCSEventsReceiver.TOWER_APP_ID.equals(appId)) {
                    final ConnectionParameter connectionParams = info.getParcelable(GCSEvent
                            .EXTRA_VEHICLE_CONNECTION_PARAMETER);
                    if(connectionParams != null){
                        connParams = connectionParams;
                        connectDrone();
                    }
                }
            }
        }
    }

    private void connectDrone(){
        if (drone != null && !drone.isConnected() && connParams!=null) {
            drone.connect(connParams);
        }
    }

    @Override
    public void onDroneEvent(String event, Bundle bundle) {
        try {
            final String action = new Intent(event).getAction();
            switch (action) {
                case AttributeEvent.STATE_DISCONNECTED:
                    PebbleKit.closeAppOnPebble(applicationContext, DP_UUID);
                    stopSelf();
                    break;
                case AttributeEvent.STATE_CONNECTED:
                    PebbleKit.startAppOnPebble(applicationContext, DP_UUID);
                    break;
                case AttributeEvent.STATE_VEHICLE_MODE:
                case AttributeEvent.BATTERY_UPDATED:
                case AttributeEvent.SPEED_UPDATED:
                    sendDataToWatchNow(drone);
                    break;
                case AttributeEvent.FOLLOW_START:
                case AttributeEvent.FOLLOW_STOP:
                    sendDataToWatchNow(drone);

                    FollowState followState = drone.getAttribute(AttributeType.FOLLOW_STATE);
                    if (followState != null) {
                        String eventLabel = null;
                        switch (followState.getState()) {
                            case FollowState.STATE_START:
                            case FollowState.STATE_RUNNING:
                                eventLabel = "FollowMe enabled";
                                break;

                            case FollowState.STATE_END:
                                eventLabel = "FollowMe disabled";
                                break;

                            case FollowState.STATE_INVALID:
                                eventLabel = "FollowMe error: invalid state";
                                break;

                            case FollowState.STATE_DRONE_DISCONNECTED:
                                eventLabel = "FollowMe error: drone not connected";
                                break;

                            case FollowState.STATE_DRONE_NOT_ARMED:
                                eventLabel = "FollowMe error: drone not armed";
                                break;
                        }

                        if (eventLabel != null) {
                            Toast.makeText(applicationContext, eventLabel, Toast.LENGTH_SHORT).show();
                        }
                    }
                    break;
            }
        }catch(Exception e){
            //TODO figure out what was messing up here
        }
    }

    @Override
    public void onDestroy() {
        drone.disconnect();
        drone.unregisterDroneListener(this);

        controlTower.unregisterDrone(drone);
        if(controlTower.isTowerConnected())
            controlTower.disconnect();

        drone = null;
        controlTower = null;
        pebbleDataHandler = null;

        handler.removeCallbacks(destroyWatchdog);
        this.stopForeground(true);
    }

    private double roundToTwoDigits(double value) {
        if(value>10)//If greater than 10, lop off the decimal
            return Math.round(value);
        else//otherwise otherwise keep the first decimal and lop off the rest
            return (double) Math.round(value * 10) / 10;
    }

    /**
     * Calls sendDataToWatchNow if the timeout of 500ms has elapsed since last
     * call or if we receive an ACK to prevent DOSing the pebble. If not, the
     * packet will be dropped. If this packet is important (e.g. mode change),
     * call sendDataToWatchNow directly.
     *
     * @param drone
     */
    public void sendDataToWatchIfTimeHasElapsed(Drone drone) {
        if (System.currentTimeMillis() - timeWhenLastTelemSent > 1500 || safeToSendNextPacketToPebble) {
            sendDataToWatchNow(drone);
            timeWhenLastTelemSent = System.currentTimeMillis();
            safeToSendNextPacketToPebble = false;
        }
    }

    /**
     * Sends a full dictionary with updated information when called. If no
     * pebble is present, the watchapp isn't installed, or the watchapp isn't
     * running, nothing will happen.
     *
     * @param drone
     */
    public void sendDataToWatchNow(Drone drone) {
        final FollowState followState = drone.getAttribute(AttributeType.FOLLOW_STATE);
        final State droneState = drone.getAttribute(AttributeType.STATE);
        if (followState == null || droneState == null)
            return;

        PebbleDictionary data = new PebbleDictionary();

        VehicleMode mode = droneState.getVehicleMode();
        if (mode == null)
            return;

        final GuidedState guidedState = drone.getAttribute(AttributeType.GUIDED_STATE);
        String modeLabel = mode.getLabel();
        if (!droneState.isArmed())
            modeLabel = "Disarmed";
        else if (followState.isEnabled())
            modeLabel = "Follow";
        else if (guidedState.isIdle())
            modeLabel = "Paused";

        data.addString(KEY_MODE, modeLabel);

        FollowType type = followState.getMode();
        if (type != null) {
            data.addString(KEY_FOLLOW_TYPE, type.getTypeLabel());
        } else
            data.addString(KEY_FOLLOW_TYPE, "none");

        final Battery droneBattery = drone.getAttribute(AttributeType.BATTERY);
        Double battVoltage = droneBattery.getBatteryVoltage();
        if (battVoltage != null)
            battVoltage = 0.0;
        String bat = "Bat: " + Double.toString(roundToTwoDigits(battVoltage)) + "V";

        final Speed droneSpeed = drone.getAttribute(AttributeType.SPEED);
        String speedValue = Double.toString(roundToTwoDigits(droneSpeed.getAirSpeed()));
        //Chop off '.0' if that's what it ends with
        if(speedValue.substring(speedValue.length()-2,speedValue.length()).equals(".0")){
            speedValue = speedValue.substring(0,speedValue.length()-2);
        }
        String speed = "Spd: " + speedValue + "m/s";

        final Altitude droneAltitude = drone.getAttribute(AttributeType.ALTITUDE);
        String altitude = "Alt: " + Double.toString(roundToTwoDigits(droneAltitude.getAltitude())) + "m";
        String telem = bat + "\n" + altitude + "\n" + speed;
        data.addString(KEY_TELEM, telem);

        data.addString(KEY_APP_VERSION, EXPECTED_APP_VERSION);

        PebbleKit.sendDataToPebble(applicationContext, DP_UUID, data);
    }

    public class PebbleReceiverHandler extends PebbleKit.PebbleDataReceiver {

        private static final int KEY_PEBBLE_REQUEST = 100;
        private static final int KEY_REQUEST_MODE_FOLLOW = 101;
        private static final int KEY_REQUEST_CYCLE_FOLLOW_TYPE = 102;
        private static final int KEY_REQUEST_PAUSE = 103;
        private static final int KEY_REQUEST_MODE_RTL = 104;
        private static final int KEY_REQUEST_CONNECT = 105;
        private static final int KEY_REQUEST_DISCONNECT = 106;

        protected PebbleReceiverHandler(UUID id) {
            super(id);
        }

        @Override
        public void receiveData(Context context, int transactionId, PebbleDictionary data) {
            PebbleKit.sendAckToPebble(applicationContext, transactionId);
            if (drone == null || !drone.isConnected())
                return;
            FollowState followMe = drone.getAttribute(AttributeType.FOLLOW_STATE);

            int request = (data.getInteger(KEY_PEBBLE_REQUEST).intValue());
            switch (request) {

                case KEY_REQUEST_CONNECT:
                    //not needed.  connections are expected to be made using a real GCS.
                    break;

                case KEY_REQUEST_DISCONNECT:
                    stopSelf();
                    break;

                case KEY_REQUEST_MODE_FOLLOW:
                    if (followMe.isEnabled()) {
                        drone.disableFollowMe();
                    } else {
                        drone.enableFollowMe(followMe.getMode());
                    }
                    break;

                case KEY_REQUEST_CYCLE_FOLLOW_TYPE:
                    List<FollowType> followTypes = Arrays.asList(FollowType.values());
                    int currentTypeIndex = followTypes.indexOf(followMe.getMode());
                    int nextTypeIndex = (currentTypeIndex + 1) % followTypes.size();
                    drone.enableFollowMe(followTypes.get(nextTypeIndex));
                    break;

                case KEY_REQUEST_PAUSE:
                    drone.pauseAtCurrentLocation();
                    break;

                case KEY_REQUEST_MODE_RTL:
                    drone.changeVehicleMode(VehicleMode.COPTER_RTL);
                    break;
            }
        }
    }

    @Override
    public void onTowerDisconnected() {

    }

    @Override
    public void onDroneConnectionFailed(ConnectionResult connectionResult) {

    }

    @Override
    public void onDroneServiceInterrupted(String s) {
        controlTower.unregisterDrone(drone);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}