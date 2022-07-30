package name.avioli.unilinks;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;

public class UniLinksPlugin
        implements FlutterPlugin,
        MethodChannel.MethodCallHandler,
        EventChannel.StreamHandler,
        ActivityAware,
        PluginRegistry.NewIntentListener {

    private static final String MESSAGES_CHANNEL = "uni_links/messages";
    private static final String EVENTS_CHANNEL = "uni_links/events";
    private static final List<String> BROWSER_PATHS = Arrays.asList("/signup", "/signup/confirmation", "/payment/cards/new", "/payment/cards");

    private BroadcastReceiver changeReceiver;

    private String initialLink;
    private String latestLink;
    private Context context;
    private boolean initialIntent = true;

    private boolean isShowOnBrowser(Intent intent) {
        final Uri data = intent.getData();
        if (data != null) {
            final String path = data.getPath();
            return BROWSER_PATHS.contains(path);
        }
        return false;
    }

    private void forwardToBrowser(@NonNull Intent i) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Intent intent = new Intent();
            intent.setAction(android.content.Intent.ACTION_VIEW);
            intent.setDataAndType(i.getData(), i.getType());
            List<ResolveInfo> activities = context.getPackageManager().queryIntentActivities(intent, 0);
            ArrayList<Intent> targetIntents = new ArrayList<Intent>();
            String thisPackageName = context.getApplicationContext().getPackageName();
            for (ResolveInfo currentInfo : activities) {
                String packageName = currentInfo.activityInfo.packageName;
                if (!thisPackageName.equals(packageName)) {
                    Intent targetIntent = new Intent(android.content.Intent.ACTION_VIEW);
                    targetIntent.setDataAndType(intent.getData(), intent.getType());
                    targetIntent.setPackage(intent.getPackage());
                    targetIntent.setComponent(new ComponentName(packageName, currentInfo.activityInfo.name));
                    targetIntents.add(targetIntent);
                }
            }
            if (targetIntents.size() > 0) {
                Intent chooserIntent = Intent.createChooser(targetIntents.remove(0), "Open with");
                chooserIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, targetIntents.toArray(new Parcelable[]{}));
                context.startActivity(chooserIntent);
                try {
                    ((Activity) context).finish();
                } catch (Exception e) {
                    //
                }
            }
        }
        // from SDK 23, queryIntentActivities only return your activity, so you need to disable you activity before forward to browser and enable it later.
        else {
            final PackageManager pm = context.getPackageManager();
            final ComponentName component = new ComponentName(context.getApplicationContext().getPackageName(), "asia.executionlab.guidenavi.MainActivity");
            pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            Intent webIntent = new Intent(Intent.ACTION_VIEW);
            webIntent.setDataAndType(i.getData(), i.getType());
            webIntent.setPackage("com.android.chrome");
//                webIntent.setComponent(new ComponentName("com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.SBrowserLauncherActivity"));
            webIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(webIntent);
            Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0);
                }
            }, 500);
        }
    }

    private void setDeepLinkingState(int state) {
        final String packageName = context.getPackageName();
        ComponentName compName = new ComponentName(packageName, packageName + ".Deeplinking");
        context.getApplicationContext().getPackageManager().setComponentEnabledSetting(
                compName,
                state,
                PackageManager.DONT_KILL_APP);
    }

    private void handleIntent(Context context, Intent intent) {
        String action = intent.getAction();
        String dataString = intent.getDataString();

        if (Intent.ACTION_VIEW.equals(action)) {
            if (initialIntent) {
                initialLink = dataString;
                initialIntent = false;
            }
            latestLink = dataString;
            if (changeReceiver != null) changeReceiver.onReceive(context, intent);
        }
    }

    @NonNull
    private BroadcastReceiver createChangeReceiver(final EventChannel.EventSink events) {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // NOTE: assuming intent.getAction() is Intent.ACTION_VIEW

                // Log.v("uni_links", String.format("received action: %s", intent.getAction()));

                String dataString = intent.getDataString();

                if (dataString == null) {
                    events.error("UNAVAILABLE", "Link unavailable", null);
                } else {
                    events.success(dataString);
                }
            }
        };
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        this.context = flutterPluginBinding.getApplicationContext();
        register(flutterPluginBinding.getBinaryMessenger(), this);
    }

    private static void register(BinaryMessenger messenger, UniLinksPlugin plugin) {
        final MethodChannel methodChannel = new MethodChannel(messenger, MESSAGES_CHANNEL);
        methodChannel.setMethodCallHandler(plugin);

        final EventChannel eventChannel = new EventChannel(messenger, EVENTS_CHANNEL);
        eventChannel.setStreamHandler(plugin);
    }

    /**
     * Plugin registration.
     */
    public static void registerWith(@NonNull PluginRegistry.Registrar registrar) {
        // Detect if we've been launched in background
        if (registrar.activity() == null) {
            return;
        }

        final UniLinksPlugin instance = new UniLinksPlugin();
        instance.context = registrar.context();
        register(registrar.messenger(), instance);

        Intent i = registrar.activity().getIntent();
        if (instance.isShowOnBrowser(i)) {
            instance.forwardToBrowser(i);
        } else {
            instance.handleIntent(registrar.context(), i);
        }

        registrar.addNewIntentListener(instance);
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
    }

    @Override
    public void onListen(Object o, EventChannel.EventSink eventSink) {
        changeReceiver = createChangeReceiver(eventSink);
    }

    @Override
    public void onCancel(Object o) {
        changeReceiver = null;
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        if (call.method.equals("getInitialLink")) {
            result.success(initialLink);
        } else if (call.method.equals("getLatestLink")) {
            result.success(latestLink);
        } else {
            result.notImplemented();
        }
    }

    @Override
    public boolean onNewIntent(Intent intent) {
        if (isShowOnBrowser(intent)) {
            this.forwardToBrowser(intent);
        } else {
            this.handleIntent(context, intent);
        }
        return false;
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding activityPluginBinding) {
        activityPluginBinding.addOnNewIntentListener(this);
        Intent i = activityPluginBinding.getActivity().getIntent();
        if (isShowOnBrowser(i)) {
            this.forwardToBrowser(i);
        } else {
            this.handleIntent(this.context, i);
        }
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
    }

    @Override
    public void onReattachedToActivityForConfigChanges(
            @NonNull ActivityPluginBinding activityPluginBinding) {
        activityPluginBinding.addOnNewIntentListener(this);
        Intent i = activityPluginBinding.getActivity().getIntent();
        if (isShowOnBrowser(i)) {
            this.forwardToBrowser(i);
        } else {
            this.handleIntent(this.context, i);
        }
    }

    @Override
    public void onDetachedFromActivity() {
    }
}
