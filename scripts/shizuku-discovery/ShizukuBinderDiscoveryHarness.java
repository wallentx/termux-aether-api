package com.termux.api.shizuku;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Standalone harness; compiled only with scripts/test_shizuku_discovery.py boundary stubs. */
public final class ShizukuBinderDiscoveryHarness {
    private static int checks;
    private static void check(boolean valid) { checks++; if (!valid) throw new AssertionError("check " + checks); }
    private static final class Packages extends PackageManager {
        final Map<String, ApplicationInfo> entries = new HashMap<>();
        void add(String name, int uid) {
            ApplicationInfo info = new ApplicationInfo();info.packageName=name;info.uid=uid;info.enabled=true;entries.put(name,info);
        }
        @Override public ApplicationInfo getApplicationInfo(String name, int flags) throws NameNotFoundException {
            check(flags==0);ApplicationInfo info=entries.get(name);if(info==null)throw new NameNotFoundException();return info;
        }
        @Override public Intent getLaunchIntentForPackage(String name) { return entries.containsKey(name)?new Intent("launch").setPackage(name):null; }
    }
    private static final class App extends Context {
        final Packages packages=new Packages();final List<Intent> sent=new ArrayList<>();String failPackage;
        @Override public PackageManager getPackageManager(){return packages;}
        @Override public String getPackageName(){return "com.termux.api";}
        @Override public ApplicationInfo getApplicationInfo(){ApplicationInfo info=new ApplicationInfo();info.uid=10445;return info;}
        @Override public void sendBroadcast(Intent request){sent.add(request);if(request.getPackage().equals(failPackage))throw new SecurityException();}
    }
    private static final class Result implements ShizukuBinderDiscovery.Listener {
        IBinder binder;int successes,failures;String reason;
        @Override public void onBinderReceived(IBinder value){binder=value;successes++;}
        @Override public void onUnavailable(String value){reason=value;failures++;}
    }
    private static final class Run {
        final App app=new App();final ShizukuBinderDiscovery discovery=new ShizukuBinderDiscovery();final Result result=new Result();
        Run(){Handler.reset();}
        void start(){discovery.start(app,result);}
    }
    private static boolean reply(Intent request,int uid,IBinder binder,boolean trailing)throws Exception{
        Binder.callingUid=uid;Parcel data=new Parcel();data.writeStrongBinder(binder);if(trailing)data.writeString("/data/app/manager/base.apk");
        IBinder callback=request.getBundleExtra("data").getBinder("binder");return callback.transact(IBinder.FIRST_CALL_TRANSACTION,data,null,IBinder.FLAG_ONEWAY);
    }
    private static void discoveryAndHandshake()throws Exception{
        Run run=new Run();run.app.packages.add(ShizukuManagers.PLUS,10601);run.start();check(run.discovery.isActive());check(run.app.sent.size()==1);
        Intent request=run.app.sent.get(0);check(request.getPackage().equals(ShizukuManagers.PLUS));check(request.getAction().equals("rikka.shizuku.intent.action.REQUEST_BINDER"));
        check((request.getFlags()&Intent.FLAG_INCLUDE_STOPPED_PACKAGES)!=0);check(request.getStringExtra("callingPackage").equals("com.termux.api"));check(request.getIntExtra("callingUid",0)==10445);
        Binder live=new Binder();check(reply(request,10601,live,true));Handler.flush();check(run.result.binder==live);check(run.result.successes==1);check(!run.discovery.isActive());
        Handler.advance(15000);check(run.result.failures==0);
        check(ShizukuManagers.launchIntent(run.app.packages).getPackage().equals(ShizukuManagers.PLUS));
    }
    private static void stockAndMissing()throws Exception{
        Run run=new Run();run.app.packages.add(ShizukuManagers.STOCK,10489);run.start();check(run.app.sent.get(0).getPackage().equals(ShizukuManagers.STOCK));
        check(reply(run.app.sent.get(0),10489,new Binder(),false));Handler.flush();check(run.result.successes==1);
        run=new Run();run.start();check(run.app.sent.isEmpty());check(run.result.reason.equals("manager_missing"));check(!run.discovery.isActive());
        run=new Run();run.app.packages.add(ShizukuManagers.PLUS,10601);run.app.packages.entries.get(ShizukuManagers.PLUS).enabled=false;run.start();check(run.result.reason.equals("manager_missing"));
        check(ShizukuManagers.present(run.app.packages).contains(ShizukuManagers.PLUS));
        check(ShizukuManagers.installed(run.app.packages).isEmpty());
    }
    private static void multipleManagersAndAuthentication()throws Exception{
        Run run=new Run();run.app.packages.add(ShizukuManagers.PLUS,10601);run.app.packages.add(ShizukuManagers.STOCK,10489);run.start();check(run.app.sent.size()==2);
        Intent plus=run.app.sent.get(0),stock=run.app.sent.get(1);
        check(!reply(plus,10489,new Binder(),false));check(!reply(plus,99999,new Binder(),false));Handler.flush();check(run.result.successes==0);check(run.discovery.isActive());
        check(reply(plus,10601,null,true));Handler.flush();check(run.result.failures==0);check(run.discovery.isActive());
        check(!reply(plus,10601,new Binder(),false));check(reply(stock,10489,new Binder(),false));Handler.flush();check(run.result.successes==1);
        Handler.advance(15000);check(run.result.failures==0);
        run=new Run();run.app.packages.add(ShizukuManagers.PLUS,10601);run.app.packages.add(ShizukuManagers.STOCK,10489);run.start();
        Binder first=new Binder();reply(run.app.sent.get(0),10601,first,true);reply(run.app.sent.get(1),10489,new Binder(),false);Handler.flush();check(run.result.binder==first);check(run.result.successes==1);
    }
    private static void staleCancelledAndTimeout()throws Exception{
        Run run=new Run();run.app.packages.add(ShizukuManagers.PLUS,10601);run.start();Intent old=run.app.sent.get(0);run.discovery.cancel();reply(old,10601,new Binder(),false);Handler.flush();check(run.result.successes==0);check(run.result.failures==0);
        run=new Run();run.app.packages.add(ShizukuManagers.PLUS,10601);run.start();old=run.app.sent.get(0);Handler.advance(5000);run.start();Intent current=run.app.sent.get(1);
        reply(old,10601,new Binder(),false);Handler.flush();check(run.result.successes==0);Handler.advance(10000);check(run.discovery.isActive());reply(current,10601,new Binder(),true);Handler.flush();check(run.result.successes==1);
        run=new Run();run.app.packages.add(ShizukuManagers.PLUS,10601);run.start();Handler.advance(15000);check(run.result.failures==1);check(run.result.reason.equals("request_timeout"));reply(run.app.sent.get(0),10601,new Binder(),false);Handler.flush();check(run.result.successes==0);check(run.result.failures==1);
    }
    private static void failedManagerAndMalformedResponse()throws Exception{
        Run run=new Run();run.app.packages.add(ShizukuManagers.PLUS,10601);run.app.packages.add(ShizukuManagers.STOCK,10489);run.app.failPackage=ShizukuManagers.PLUS;run.start();check(run.discovery.isActive());reply(run.app.sent.get(1),10489,new Binder(),false);Handler.flush();check(run.result.successes==1);
        run=new Run();run.app.packages.add(ShizukuManagers.PLUS,10601);run.start();Binder.callingUid=10601;IBinder callback=run.app.sent.get(0).getBundleExtra("data").getBinder("binder");
        check(!callback.transact(IBinder.FIRST_CALL_TRANSACTION,new Parcel(),null,IBinder.FLAG_ONEWAY));check(!callback.transact(99,new Parcel(),null,0));reply(run.app.sent.get(0),10601,new Binder(),true);Handler.flush();check(run.result.successes==1);
        run=new Run();run.app.packages.add(ShizukuManagers.PLUS,10601);run.start();Binder dead=new Binder();dead.alive=false;reply(run.app.sent.get(0),10601,dead,false);Handler.flush();check(run.result.reason.equals("service_not_running"));Handler.advance(15000);check(run.result.failures==1);
    }
    public static void main(String[] args)throws Exception{
        discoveryAndHandshake();stockAndMissing();multipleManagersAndAuthentication();staleCancelledAndTimeout();failedManagerAndMalformedResponse();
        System.out.println("ShizukuBinderDiscovery: "+checks+" checks passed");
    }
}
