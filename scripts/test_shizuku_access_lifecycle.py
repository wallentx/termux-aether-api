#!/usr/bin/env python3
"""Exercise actual consent Activity lifecycle with deterministic UI/SDK boundaries."""
from pathlib import Path
import importlib.util
import os
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("discovery_boundaries", ROOT / "scripts/test_shizuku_discovery.py")
discovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(discovery)
STUBS = dict(discovery.STUBS)
STUBS.update({
    "android/annotation/SuppressLint.java": "package android.annotation;public @interface SuppressLint {String[] value();}",
    "android/R.java": "package android;public final class R {public static class style {public static final int TextAppearance_Material_Headline=1;}}",
    "android/content/pm/PackageManager.java": STUBS["android/content/pm/PackageManager.java"].replace("public abstract class PackageManager {", "public abstract class PackageManager {public static final int PERMISSION_GRANTED=0,PERMISSION_DENIED=-1;"),
    "android/content/Intent.java": STUBS["android/content/Intent.java"].replace("public class Intent {", "public class Intent {public Intent putExtra(String k,boolean b){extras.put(k,b);return this;}public boolean getBooleanExtra(String k,boolean d){return extras.containsKey(k)?(boolean)extras.get(k):d;}"),
    "android/os/Bundle.java": "package android.os;import java.util.*;public class Bundle {private Map<String,Object> values=new HashMap<>();public void putBinder(String k,IBinder b){values.put(k,b);}public IBinder getBinder(String k){return (IBinder)values.get(k);}public void putBoolean(String k,boolean b){values.put(k,b);}public boolean getBoolean(String k){return values.containsKey(k)&&(boolean)values.get(k);}}",
    "android/util/DisplayMetrics.java": "package android.util;public class DisplayMetrics {public float density=1;}",
    "android/content/res/Resources.java": "package android.content.res;import android.util.DisplayMetrics;public class Resources {public DisplayMetrics getDisplayMetrics(){return new DisplayMetrics();}}",
    "android/view/View.java": "package android.view;public class View {public interface OnClickListener {void onClick(View v);}public void setPadding(int a,int b,int c,int d){}}",
    "android/view/Window.java": "package android.view;public class Window {public View getDecorView(){return new View();}}",
    "android/app/Activity.java": """package android.app;import android.content.*;import android.content.pm.*;import android.content.res.Resources;import android.os.Bundle;import android.view.*;import java.util.*;
public class Activity extends Context {public PackageManager packages;public Intent intent=new Intent("open");public List<Intent> broadcasts=new ArrayList<>();private boolean destroyed;
protected void onCreate(Bundle b){}protected void onResume(){}protected void onPause(){}protected void onDestroy(){destroyed=true;}protected void onSaveInstanceState(Bundle b){}
public PackageManager getPackageManager(){return packages;}public String getPackageName(){return "com.termux.api";}public ApplicationInfo getApplicationInfo(){ApplicationInfo i=new ApplicationInfo();i.uid=10445;return i;}public void sendBroadcast(Intent i){broadcasts.add(i);}
public void runOnUiThread(Runnable r){r.run();}public boolean isFinishing(){return false;}public boolean isDestroyed(){return destroyed;}public void setTitle(int i){}public void setContentView(View v){}public Window getWindow(){return new Window();}public Resources getResources(){return new Resources();}public Intent getIntent(){return intent;}public void startActivity(Intent i){}
}""",
    "android/widget/TextView.java": "package android.widget;import android.content.Context;import android.view.View;public class TextView extends View {public int text;public TextView(Context c){}public void setText(int value){text=value;}public void setTextAppearance(int value){}}",
    "android/widget/Button.java": "package android.widget;import android.content.Context;public class Button extends TextView {private OnClickListener listener;public boolean enabled;public Button(Context c){super(c);}public void setOnClickListener(OnClickListener l){listener=l;}public void setEnabled(boolean e){enabled=e;}public void performClick(){listener.onClick(this);}}",
    "android/widget/LinearLayout.java": "package android.widget;import android.content.Context;import android.view.View;public class LinearLayout extends View {public static final int VERTICAL=1;public LinearLayout(Context c){}public void setOrientation(int i){}public void addView(View v){}}",
    "android/widget/ScrollView.java": "package android.widget;import android.content.Context;import android.view.View;public class ScrollView extends View {public ScrollView(Context c){}public void setFillViewport(boolean b){}public void addView(View v){}}",
    "androidx/core/graphics/Insets.java": "package androidx.core.graphics;public class Insets {public int left,top,right,bottom;}",
    "androidx/core/view/WindowInsetsCompat.java": "package androidx.core.view;import androidx.core.graphics.Insets;public class WindowInsetsCompat {public Insets getInsets(int i){return new Insets();}public static class Type {public static int systemBars(){return 1;}public static int displayCutout(){return 2;}}}",
    "androidx/core/view/ViewCompat.java": "package androidx.core.view;import android.view.View;public class ViewCompat {public interface Listener {WindowInsetsCompat apply(View v,WindowInsetsCompat i);}public static void setOnApplyWindowInsetsListener(View v,Listener l){}public static void requestApplyInsets(View v){}}",
    "androidx/core/view/WindowCompat.java": "package androidx.core.view;import android.view.*;public class WindowCompat {public static void setDecorFitsSystemWindows(Window w,boolean b){}public static Controller getInsetsController(Window w,View v){return new Controller();}public static class Controller {public void setAppearanceLightStatusBars(boolean b){}public void setAppearanceLightNavigationBars(boolean b){}}}",
    "rikka/shizuku/Shizuku.java": """package rikka.shizuku;import android.os.IBinder;import java.util.*;public class Shizuku {
public static IBinder binder;public static int requests,permission=-1;public interface OnBinderReceivedListener {void onBinderReceived();}public interface OnBinderDeadListener {void onBinderDead();}public interface OnRequestPermissionResultListener {void onRequestPermissionResult(int code,int result);}
private static List<OnBinderReceivedListener> received=new ArrayList<>();public static void reset(){binder=null;requests=0;permission=-1;received.clear();}
public static boolean pingBinder(){return binder!=null&&binder.pingBinder();}public static int getVersion(){return 13;}public static int checkSelfPermission(){return permission;}public static boolean shouldShowRequestPermissionRationale(){return false;}public static void requestPermission(int code){requests++;}
public static void addBinderReceivedListenerSticky(OnBinderReceivedListener l){received.add(l);if(pingBinder())l.onBinderReceived();}public static void removeBinderReceivedListener(OnBinderReceivedListener l){received.remove(l);}public static void addBinderDeadListener(OnBinderDeadListener l){}public static void removeBinderDeadListener(OnBinderDeadListener l){}public static void addRequestPermissionResultListener(OnRequestPermissionResultListener l){}public static void removeRequestPermissionResultListener(OnRequestPermissionResultListener l){}
public static void onBinderReceived(IBinder b,String p){binder=b;for(OnBinderReceivedListener l:new ArrayList<>(received))l.onBinderReceived();}
}""",
})


def main():
    names = re.findall(r'<string name="([^"]+)"', (ROOT / "app/src/main/res/values/shizuku_strings.xml").read_text())
    STUBS["com/termux/api/R.java"] = "package com.termux.api;public class R {public static class string {" + "".join(
        "public static final int " + name + "=" + str(i + 1) + ";" for i, name in enumerate(names)) + "}}"
    with tempfile.TemporaryDirectory(prefix="shizuku-lifecycle-", dir=os.environ.get("TMPDIR")) as directory:
        work = Path(directory)
        sources = []
        for name, content in STUBS.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            sources.append(str(path))
        sources += [str(ROOT / "app/src/main/java/com/termux/api/shizuku" / name)
                    for name in ("ShizukuManagers.java", "ShizukuBinderDiscovery.java")]
        sources += [str(ROOT / "app/src/main/java/com/termux/api/activities/ShizukuAccessActivity.java"),
                    str(ROOT / "scripts/shizuku-discovery/ShizukuAccessLifecycleHarness.java")]
        subprocess.run(["javac", "--release", "11", "-d", str(work), *sources], check=True)
        subprocess.run(["java", "-cp", str(work), "com.termux.api.activities.ShizukuAccessLifecycleHarness"], check=True)


if __name__ == "__main__":
    main()
