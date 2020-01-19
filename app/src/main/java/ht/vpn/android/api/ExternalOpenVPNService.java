/*
 * Copyright (c) 2012-2014 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package ht.vpn.android.api;

import android.annotation.TargetApi;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.VpnService;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import java.io.IOException;
import java.io.StringReader;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import ht.vpn.android.R;
import ht.vpn.android.VpnProfile;
import de.blinkt.openvpn.core.ConfigParser;
import de.blinkt.openvpn.core.ConfigParser.ConfigParseError;
import de.blinkt.openvpn.core.OpenVPNService;
import de.blinkt.openvpn.core.OpenVPNService.LocalBinder;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.core.VPNLaunchHelper;
import de.blinkt.openvpn.core.VpnStatus;
import de.blinkt.openvpn.core.VpnStatus.ConnectionStatus;
import de.blinkt.openvpn.core.VpnStatus.StateListener;
import ht.vpn.android.activities.ConfirmAPIActivity;
import ht.vpn.android.activities.GrantPermissionsActivity;

@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1)
public class ExternalOpenVPNService extends Service implements StateListener {

    private static final int SEND_TOALL = 0;

    final RemoteCallbackList<IOpenVPNStatusCallback> mCallbacks =
            new RemoteCallbackList<>();

    private OpenVPNService mService;
    private ExternalAppDatabase mExtAppDb;


    private ServiceConnection mConnection = new ServiceConnection() {


        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            LocalBinder binder = (LocalBinder) service;
            mService = binder.getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mService = null;
        }

    };

    @Override
    public void onCreate() {
        super.onCreate();
        VpnStatus.addStateListener(this);
        mExtAppDb = new ExternalAppDatabase(this);

        Intent intent = new Intent(getBaseContext(), OpenVPNService.class);
        intent.setAction(OpenVPNService.START_SERVICE);

        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        mHandler.setService(this);
    }

    private final IOpenVPNAPIService.Stub mBinder = new IOpenVPNAPIService.Stub() {

        private String checkOpenVPNPermission() throws SecurityRemoteException {
            PackageManager pm = getPackageManager();

            Set<String> set = new HashSet<>(mExtAppDb.getExtAppList());
            for (String appPackage : set) {
                ApplicationInfo app;
                try {
                    app = pm.getApplicationInfo(appPackage, 0);
                    if (Binder.getCallingUid() == app.uid) {
                        return appPackage;
                    }
                } catch (NameNotFoundException e) {
                    // App not found. Remove it from the list
                    mExtAppDb.removeApp(appPackage);
                }
            }
            throw new SecurityException("Unauthorized OpenVPN API Caller");
        }

        public boolean isConnectedOrConnecting() {
            return mMostRecentState.level == ConnectionStatus.LEVEL_CONNECTED || mMostRecentState.level == ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET || mMostRecentState.level == ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED;
        }

        @Override
        public List<APIVpnProfile> getProfiles() throws RemoteException {
            checkOpenVPNPermission();

            ProfileManager pm = ProfileManager.getInstance(getBaseContext());

            List<APIVpnProfile> profiles = new LinkedList<>();

            for (VpnProfile vp : pm.getProfiles()) {
                if (!vp.profileDeleted)
                    profiles.add(new APIVpnProfile(vp.getUUIDString(), vp.mName, vp.mUserEditable, vp.mProfileCreator));
            }

            return profiles;
        }


        private void startProfile(VpnProfile vp) {
            Intent vpnPermissionIntent = VpnService.prepare(ExternalOpenVPNService.this);
            /* Check if we need to show the confirmation dialog,
             * Check if we need to ask for username/password */

            int needpw = vp.needUserPWInput(false);

            if (vpnPermissionIntent != null || needpw != 0) {
                Intent shortVPNIntent = new Intent(Intent.ACTION_MAIN);
                shortVPNIntent.setClass(getBaseContext(), ht.vpn.android.LaunchVPN.class);
                shortVPNIntent.putExtra(ht.vpn.android.LaunchVPN.EXTRA_KEY, vp.getUUIDString());
                shortVPNIntent.putExtra(ht.vpn.android.LaunchVPN.EXTRA_HIDELOG, true);
                shortVPNIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(shortVPNIntent);
            } else {
                VPNLaunchHelper.startOpenVpn(vp, getBaseContext());
            }

        }

        @Override
        public void startProfile(String profileUUID) throws RemoteException {
            checkOpenVPNPermission();

            VpnProfile vp = ProfileManager.get(getBaseContext(), profileUUID);
            if (vp.checkProfile(getApplicationContext()) != R.string.no_error_found)
                throw new RemoteException(getString(vp.checkProfile(getApplicationContext())));

            startProfile(vp);
        }

        public void startVPN(String name, String inlineConfig) throws RemoteException {
            checkOpenVPNPermission();

            ConfigParser cp = new ConfigParser();
            try {
                cp.parseConfig(new StringReader(inlineConfig));
                VpnProfile vp = cp.convertProfile();
                vp.mName = name;
                if (vp.checkProfile(getApplicationContext()) != R.string.no_error_found)
                    throw new RemoteException(getString(vp.checkProfile(getApplicationContext())));

                /*int needpw = vp.needUserPWInput(false);
                if(needpw !=0)
                    throw new RemoteException("The inline file would require user input: " + getString(needpw));
                    */

                ProfileManager.setTemporaryProfile(vp);
                startProfile(vp);
            } catch (IOException | ConfigParseError e) {
                throw new RemoteException(e.getMessage());
            }
        }


        @Override
        public boolean addVPNProfile(String name, String config) throws RemoteException {
            return addNewVPNProfile(name, true, config) != null;
        }


        @Override
        public APIVpnProfile addNewVPNProfile(String name, boolean userEditable, String config) throws RemoteException {
            String callingPackage = checkOpenVPNPermission();

            ConfigParser cp = new ConfigParser();
            try {
                cp.parseConfig(new StringReader(config));
                VpnProfile vp = cp.convertProfile();
                vp.mName = name;
                vp.mProfileCreator = callingPackage;
                vp.mUserEditable = userEditable;
                ProfileManager pm = ProfileManager.getInstance(getBaseContext());
                pm.addProfile(vp);
                pm.saveProfile(ExternalOpenVPNService.this, vp);
                pm.saveProfileList(ExternalOpenVPNService.this);
                return new APIVpnProfile(vp.getUUIDString(), vp.mName, vp.mUserEditable, vp.mProfileCreator);
            } catch (IOException e) {
                VpnStatus.logException(e);
                return null;
            } catch (ConfigParseError e) {
                VpnStatus.logException(e);
                return null;
            }
        }

        @Override
        public void removeProfile(String profileUUID) throws RemoteException {
            checkOpenVPNPermission();
            ProfileManager pm = ProfileManager.getInstance(getBaseContext());
            VpnProfile vp = ProfileManager.get(getBaseContext(), profileUUID);
            pm.removeProfile(ExternalOpenVPNService.this, vp);
        }

        @Override
        public boolean protectSocket(ParcelFileDescriptor pfd) throws RemoteException {
            checkOpenVPNPermission();
            try {
                boolean success = mService.protect(pfd.getFd());
                pfd.close();
                return success;
            } catch (IOException e) {
                throw new RemoteException(e.getMessage());
            }
        }


        @Override
        public Intent prepare(String packageName) {
            if (new ExternalAppDatabase(ExternalOpenVPNService.this).isAllowed(packageName))
                return null;

            Intent intent = new Intent();
            intent.setClass(ExternalOpenVPNService.this, ConfirmAPIActivity.class);
            return intent;
        }

        @Override
        public Intent prepareVPNService() throws RemoteException {
            checkOpenVPNPermission();

            if (VpnService.prepare(ExternalOpenVPNService.this) == null)
                return null;
            else
                return new Intent(getBaseContext(), GrantPermissionsActivity.class);
        }


        @Override
        public void registerStatusCallback(IOpenVPNStatusCallback cb)
                throws RemoteException {
            checkOpenVPNPermission();

            if (cb != null) {
                cb.newStatus(mMostRecentState.vpnUUID, mMostRecentState.state,
                        mMostRecentState.logmessage, mMostRecentState.level.name());
                mCallbacks.register(cb);
            }


        }

        @Override
        public void unregisterStatusCallback(IOpenVPNStatusCallback cb)
                throws RemoteException {
            checkOpenVPNPermission();

            if (cb != null)
                mCallbacks.unregister(cb);
        }

        @Override
        public void disconnect() throws RemoteException {
            checkOpenVPNPermission();
            if (mService != null && mService.getManagement() != null)
                mService.getManagement().stopVPN();
        }

        @Override
        public void pause() throws RemoteException {
            checkOpenVPNPermission();
            if (mService != null)
                mService.userPause(true);
        }

        @Override
        public void resume() throws RemoteException {
            checkOpenVPNPermission();
            if (mService != null)
                mService.userPause(false);

        }
    };


    private UpdateMessage mMostRecentState;

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mCallbacks.kill();
        unbindService(mConnection);
        VpnStatus.removeStateListener(this);
    }

    class UpdateMessage {
        public String state;
        public String logmessage;
        public ConnectionStatus level;
        public String vpnUUID;

        public UpdateMessage(String state, String logmessage, ConnectionStatus level) {
            this.state = state;
            this.logmessage = logmessage;
            this.level = level;
        }
    }

    @Override
    public void updateState(String state, String logmessage, int resid, ConnectionStatus level) {
        mMostRecentState = new UpdateMessage(state, logmessage, level);
        if (ProfileManager.getLastConnectedVpn() != null)
            mMostRecentState.vpnUUID = ProfileManager.getLastConnectedVpn().getUUIDString();

        Message msg = mHandler.obtainMessage(SEND_TOALL, mMostRecentState);
        msg.sendToTarget();

    }

    private static final OpenVPNServiceHandler mHandler = new OpenVPNServiceHandler();


    static class OpenVPNServiceHandler extends Handler {
        WeakReference<ExternalOpenVPNService> service = null;

        private void setService(ExternalOpenVPNService eos) {
            service = new WeakReference<>(eos);
        }

        @Override
        public void handleMessage(Message msg) {

            RemoteCallbackList<IOpenVPNStatusCallback> callbacks;
            switch (msg.what) {
                case SEND_TOALL:
                    if (service == null || service.get() == null)
                        return;

                    callbacks = service.get().mCallbacks;


                    // Broadcast to all clients the new value.
                    final int N = callbacks.beginBroadcast();
                    for (int i = 0; i < N; i++) {
                        try {
                            sendUpdate(callbacks.getBroadcastItem(i), (UpdateMessage) msg.obj);
                        } catch (RemoteException e) {
                            // The RemoteCallbackList will take care of removing
                            // the dead object for us.
                        }
                    }
                    callbacks.finishBroadcast();
                    break;
            }
        }

        private void sendUpdate(IOpenVPNStatusCallback broadcastItem,
                                UpdateMessage um) throws RemoteException {
            broadcastItem.newStatus(um.vpnUUID, um.state, um.logmessage, um.level.name());
        }
    }


}