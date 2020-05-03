package com.uce.handle;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.util.Log;

import com.uce.crash.CrashReportingManager;

import org.jetbrains.annotations.NotNull;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import androidx.annotation.NonNull;

public final class UCEHandler {
    static final String EXTRA_STACK_TRACE = "EXTRA_STACK_TRACE";
    static final String EXTRA_ACTIVITY_LOG = "EXTRA_ACTIVITY_LOG";
    private final static String TAG = "UCEHandler";
    private static final String UCE_HANDLER_PACKAGE_NAME = "com.uce";
    private static final String DEFAULT_HANDLER_PACKAGE_NAME = "com.android.internal.os";
    private static final String INTENT_ACTION_ERROR_ACTIVITY = "com.uce.ERROR";

    private static final int MAX_STACK_TRACE_SIZE = 131071; //128 KB - 1
    private static final int MAX_ACTIVITIES_IN_LOG = 50;
    private static final String SHARED_PREFERENCES_FILE = "uceh_preferences";
    private static final String SHARED_PREFERENCES_FIELD_TIMESTAMP = "last_crash_timestamp";
    private static final Deque<String> activityLog = new ArrayDeque<>(MAX_ACTIVITIES_IN_LOG);
    static String COMMA_SEPARATED_EMAIL_ADDRESSES;

    @SuppressLint("StaticFieldLeak")
    private static Application application;

    private static boolean isInBackground = true;
    private static boolean isBackgroundMode;
    private static boolean isUCEHEnabled;
    private static boolean isTrackActivitiesEnabled;

    private static Class<? extends Activity> restartActivityClass = null;
    private static Class<? extends Activity> errorActivityClass = null;

    private static WeakReference<Activity> lastActivityCreated = new WeakReference<>(null);

    private UCEHandler(@NonNull Builder builder) {
        isUCEHEnabled = builder.isUCEHEnabled;
        restartActivityClass = builder.restartActivityClass;
        isTrackActivitiesEnabled = builder.isTrackActivitiesEnabled;
        isBackgroundMode = builder.isBackgroundModeEnabled;
        COMMA_SEPARATED_EMAIL_ADDRESSES = builder.commaSeparatedEmailAddresses;
        errorActivityClass = builder.errorActivityClass;
        setUCEHandler(builder.context);
    }

    private static void setUCEHandler(final Context context) {
        try {
            if (context != null) {
                final Thread.UncaughtExceptionHandler oldHandler = Thread.getDefaultUncaughtExceptionHandler();
                if (oldHandler != null && oldHandler.getClass().getName().startsWith(UCE_HANDLER_PACKAGE_NAME)) {
                    Log.e(TAG, "UCEHandler was already installed, doing nothing!");
                } else {
                    if (oldHandler != null && !oldHandler.getClass().getName().startsWith(DEFAULT_HANDLER_PACKAGE_NAME)) {
                        Log.e(TAG, "You already have an UncaughtExceptionHandler. If you use a custom UncaughtExceptionHandler, it should be initialized after UCEHandler! Installing anyway, but your original handler will not be called.");
                    }
                    application = (Application) context.getApplicationContext();
                    //Setup UCE Handler.
                    Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                        @Override
                        public void uncaughtException(@NotNull Thread thread, @NotNull final Throwable throwable) {
                            CrashReportingManager.logException(throwable, true);

                            if (isUCEHEnabled) {
                                Log.e(TAG, "App crashed, executing UCEHandler's UncaughtExceptionHandler", throwable);
                                if (hasCrashedInTheLastSeconds(application)) {
                                    Log.e(TAG, "App already crashed recently, not starting custom error activity because we could enter a restart loop. Are you sure that your app does not crash directly on init?", throwable);
                                    if (oldHandler != null) {
                                        oldHandler.uncaughtException(thread, throwable);
                                        return;
                                    }
                                } else {
                                    if (errorActivityClass == null) {
                                        errorActivityClass = guessErrorActivityClass(application);
                                    }

                                    setLastCrashTimestamp(application, new Date().getTime());
                                    if (!isInBackground || isBackgroundMode) {
                                        final Intent intent = new Intent(application, errorActivityClass);
                                        StringWriter sw = new StringWriter();
                                        PrintWriter pw = new PrintWriter(sw);
                                        throwable.printStackTrace(pw);
                                        String stackTraceString = sw.toString();
                                        if (stackTraceString.length() > MAX_STACK_TRACE_SIZE) {
                                            String disclaimer = " [stack trace too large]";
                                            stackTraceString = stackTraceString.substring(0, MAX_STACK_TRACE_SIZE - disclaimer.length()) + disclaimer;
                                        }
                                        intent.putExtra(EXTRA_STACK_TRACE, stackTraceString);
                                        if (isTrackActivitiesEnabled) {
                                            StringBuilder activityLogStringBuilder = new StringBuilder();
                                            while (!activityLog.isEmpty()) {
                                                activityLogStringBuilder.append(activityLog.poll());
                                            }
                                            intent.putExtra(EXTRA_ACTIVITY_LOG, activityLogStringBuilder.toString());
                                        }
                                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                        application.startActivity(intent);
                                    } else {
                                        if (oldHandler != null) {
                                            oldHandler.uncaughtException(thread, throwable);
                                            return;
                                        }
                                        //If it is null (should not be), we let it continue and kill the process or it will be stuck
                                    }
                                }
                                final Activity lastActivity = lastActivityCreated.get();
                                if (lastActivity != null) {
                                    lastActivity.finish();
                                    lastActivityCreated.clear();
                                }
                                killCurrentProcess();
                            } else if (oldHandler != null) {
                                //Pass control to old uncaught exception handler
                                oldHandler.uncaughtException(thread, throwable);
                            }
                        }
                    });
                    application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                        final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                        int currentlyStartedActivities = 0;

                        @Override
                        public void onActivityCreated(@NotNull Activity activity, Bundle savedInstanceState) {
                            if (activity.getClass() != errorActivityClass) {
                                lastActivityCreated = new WeakReference<>(activity);
                            }
                            if (isTrackActivitiesEnabled) {
                                activityLog.add(dateFormat.format(new Date()) + ": " + activity.getClass().getSimpleName() + " created\n");
                            }
                        }

                        @Override
                        public void onActivityStarted(@NotNull Activity activity) {
                            currentlyStartedActivities++;
                            isInBackground = (currentlyStartedActivities == 0);
                        }

                        @Override
                        public void onActivityResumed(@NotNull Activity activity) {
                            if (isTrackActivitiesEnabled) {
                                activityLog.add(dateFormat.format(new Date()) + ": " + activity.getClass().getSimpleName() + " resumed\n");
                            }
                        }

                        @Override
                        public void onActivityPaused(@NotNull Activity activity) {
                            if (isTrackActivitiesEnabled) {
                                activityLog.add(dateFormat.format(new Date()) + ": " + activity.getClass().getSimpleName() + " paused\n");
                            }
                        }

                        @Override
                        public void onActivityStopped(@NotNull Activity activity) {
                            currentlyStartedActivities--;
                            isInBackground = (currentlyStartedActivities == 0);
                        }

                        @Override
                        public void onActivitySaveInstanceState(@NotNull Activity activity, @NotNull Bundle outState) {
                        }

                        @Override
                        public void onActivityDestroyed(@NotNull Activity activity) {
                            if (isTrackActivitiesEnabled) {
                                activityLog.add(dateFormat.format(new Date()) + ": " + activity.getClass().getSimpleName() + " destroyed\n");
                            }
                        }
                    });
                }
                Log.i(TAG, "UCEHandler has been installed.");
            } else {
                Log.e(TAG, "Context can not be null");
            }
        } catch (Throwable throwable) {
            Log.e(TAG, "UCEHandler can not be initialized. Help making it better by reporting this as a bug.", throwable);
        }
    }

    /**
     * INTERNAL method that tells if the app has crashed in the last seconds.
     * This is used to avoid restart loops.
     *
     * @return true if the app has crashed in the last seconds, false otherwise.
     */
    private static boolean hasCrashedInTheLastSeconds(Context context) {
        long lastTimestamp = getLastCrashTimestamp(context);
        long currentTimestamp = new Date().getTime();
        return (lastTimestamp <= currentTimestamp && currentTimestamp - lastTimestamp < 3000);
    }

    @SuppressLint("ApplySharedPref")
    private static void setLastCrashTimestamp(Context context, long timestamp) {
        context.getSharedPreferences(SHARED_PREFERENCES_FILE, Context.MODE_PRIVATE).edit().putLong(SHARED_PREFERENCES_FIELD_TIMESTAMP, timestamp).commit();
    }

    private static void killCurrentProcess() {
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(10);
    }

    private static long getLastCrashTimestamp(Context context) {
        return context.getSharedPreferences(SHARED_PREFERENCES_FILE, Context.MODE_PRIVATE).getLong(SHARED_PREFERENCES_FIELD_TIMESTAMP, -1);
    }

    public static void closeApplication(Activity activity) {
        activity.finish();
        killCurrentProcess();
    }

    public static void restartApplication(@NonNull Activity activity) {
        if (restartActivityClass == null) {
            return;
        }
        Intent intent = new Intent(activity, restartActivityClass);
        restartApplicationWithIntent(activity, intent);
    }

    private static void restartApplicationWithIntent(@NonNull Activity activity, @NonNull Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (intent.getComponent() != null) {
            //If the class name has been set, we force it to simulate a Launcher launch.
            //If we don't do this, if you restart from the error activity, then press home,
            //and then launch the activity from the launcher, the main activity appears twice on the backstack.
            //This will most likely not have any detrimental effect because if you set the Intent component,
            //if will always be launched regardless of the actions specified here.
            intent.setAction(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
        }
        activity.finish();
        activity.startActivity(intent);
        killCurrentProcess();
    }

    private static Class<? extends Activity> getErrorActivityClassWithIntentFilter(@NonNull Context context) {
        Intent searchedIntent = new Intent().setAction(INTENT_ACTION_ERROR_ACTIVITY).setPackage(context.getPackageName());
        List<ResolveInfo> resolveInfos = context.getPackageManager().queryIntentActivities(searchedIntent,
                PackageManager.GET_RESOLVED_FILTER);

        if (resolveInfos.size() > 0) {
            ResolveInfo resolveInfo = resolveInfos.get(0);
            try {
                return Class.forName(resolveInfo.activityInfo.name).asSubclass(Activity.class);
            } catch (ClassNotFoundException e) {
                //Should not happen, print it to the log!
                Log.e(TAG, "Failed when resolving the error activity class via intent filter, stack trace follows!", e);
            }
        }

        return null;
    }

    @NonNull
    private static Class<? extends Activity> guessErrorActivityClass(@NonNull Context context) {
        Class<? extends Activity> resolvedActivityClass;

        //If action is defined, use that
        resolvedActivityClass = getErrorActivityClassWithIntentFilter(context);

        //Else, get the default error activity
        if (resolvedActivityClass == null) {
            resolvedActivityClass = UCEDefaultActivity.class;
        }

        return resolvedActivityClass;
    }

    public static class Builder {
        private Context context;
        private boolean isUCEHEnabled = true;
        private String commaSeparatedEmailAddresses;
        private Class<? extends Activity> restartActivityClass = null;
        private Class<? extends Activity> errorActivityClass = null;

        private boolean isTrackActivitiesEnabled = false;
        private boolean isBackgroundModeEnabled = true;

        public Builder(Context context) {
            this.context = context;
        }

        public Builder setUCEHEnabled(boolean isUCEHEnabled) {
            this.isUCEHEnabled = isUCEHEnabled;
            return this;
        }

        public Builder setTrackActivitiesEnabled(boolean isTrackActivitiesEnabled) {
            this.isTrackActivitiesEnabled = isTrackActivitiesEnabled;
            return this;
        }

        public Builder setBackgroundModeEnabled(boolean isBackgroundModeEnabled) {
            this.isBackgroundModeEnabled = isBackgroundModeEnabled;
            return this;
        }

        public Builder addCommaSeparatedEmailAddresses(String commaSeparatedEmailAddresses) {
            this.commaSeparatedEmailAddresses = (commaSeparatedEmailAddresses != null) ? commaSeparatedEmailAddresses : "";
            return this;
        }

        public Class<? extends Activity> getRestartActivityClass() {
            return restartActivityClass;
        }

        public Class<? extends Activity> getErrorActivityClass() {
            return errorActivityClass;
        }

        public Builder setErrorActivityClass(Class<? extends Activity> errorActivityClass) {
            this.errorActivityClass = errorActivityClass;
            return this;
        }

        public Builder setRestartActivityClass(Class<? extends Activity> restartActivityClass) {
            this.restartActivityClass = restartActivityClass;
            return this;
        }

        public UCEHandler build() {
            return new UCEHandler(this);
        }
    }
}
