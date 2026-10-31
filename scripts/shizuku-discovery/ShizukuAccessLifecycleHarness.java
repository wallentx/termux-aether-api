package com.termux.api.activities;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.*;
import android.widget.Button;
import java.lang.reflect.Field;
import rikka.shizuku.Shizuku;

/** Drives production Activity lifecycle with controlled framework/SDK boundaries. */
public final class ShizukuAccessLifecycleHarness {
    static int checks;
    static void check(boolean good){checks++;if(!good)throw new AssertionError("check "+checks);}
    static final class Packages extends PackageManager {
        final ApplicationInfo info=new ApplicationInfo();
        Packages(){info.packageName="af.shizuku.plus.api";info.uid=10601;info.enabled=true;}
        @Override public ApplicationInfo getApplicationInfo(String name,int flags)throws NameNotFoundException{
            if(!name.equals(info.packageName))throw new NameNotFoundException();return info;
        }
        @Override public Intent getLaunchIntentForPackage(String name){return new Intent("launch").setPackage(name);}
    }
    static ShizukuAccessActivity create(boolean explicit,Bundle state){
        ShizukuAccessActivity activity=new ShizukuAccessActivity();activity.packages=new Packages();
        activity.intent.putExtra("request_permission",explicit);activity.onCreate(state);return activity;
    }
    static void reset(){Handler.reset();Shizuku.reset();}
    static void reply(Intent request)throws Exception{
        Binder.callingUid=10601;Parcel data=new Parcel();data.writeStrongBinder(new Binder());data.writeString("/data/app/manager/base.apk");
        request.getBundleExtra("data").getBinder("binder").transact(1,data,null,1);Handler.flush();
    }
    static void pendingRequestSurvivesRecreation()throws Exception{
        reset();ShizukuAccessActivity first=create(true,null);first.onResume();check(first.broadcasts.size()==1);check(Shizuku.requests==0);
        Intent old=first.broadcasts.get(0);Bundle state=new Bundle();first.onSaveInstanceState(state);
        check(state.getBoolean("automatic_request_used"));check(state.getBoolean("request_when_connected"));
        first.onPause();first.onDestroy();reply(old);check(Shizuku.binder==null);check(Shizuku.requests==0);
        ShizukuAccessActivity second=create(true,state);second.onResume();check(second.broadcasts.size()==1);check(Shizuku.requests==0);
        reply(second.broadcasts.get(0));check(Shizuku.requests==1);Bundle after=new Bundle();second.onSaveInstanceState(after);check(!after.getBoolean("request_when_connected"));
        second.onPause();second.onResume();check(Shizuku.requests==1);second.onDestroy();
    }
    static void pausedReplyDefersConsent()throws Exception{
        reset();ShizukuAccessActivity activity=create(true,null);activity.onResume();Intent request=activity.broadcasts.get(0);activity.onPause();reply(request);
        check(Shizuku.pingBinder());check(Shizuku.requests==0);Bundle state=new Bundle();activity.onSaveInstanceState(state);check(state.getBoolean("request_when_connected"));
        activity.onResume();check(Shizuku.requests==1);check(activity.broadcasts.size()==1);activity.onDestroy();
    }
    static void passiveOpenAndManualRequest()throws Exception{
        reset();ShizukuAccessActivity activity=create(false,null);activity.onResume();check(activity.broadcasts.isEmpty());check(Shizuku.requests==0);
        Field field=ShizukuAccessActivity.class.getDeclaredField("request");field.setAccessible(true);((Button)field.get(activity)).performClick();check(activity.broadcasts.size()==1);
        reply(activity.broadcasts.get(0));check(Shizuku.requests==1);activity.onDestroy();
        reset();Shizuku.binder=new Binder();activity=create(false,null);activity.onResume();check(Shizuku.requests==0);check(activity.broadcasts.isEmpty());activity.onDestroy();
    }
    public static void main(String[] args)throws Exception{
        pendingRequestSurvivesRecreation();pausedReplyDefersConsent();passiveOpenAndManualRequest();
        System.out.println("ShizukuAccessActivity lifecycle: "+checks+" checks passed");
    }
}
