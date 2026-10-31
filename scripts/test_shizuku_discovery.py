#!/usr/bin/env python3
"""Compile production discovery against controlled Android boundary stubs; no device changes."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "android/content/Context.java": """package android.content; import android.content.pm.*;
public abstract class Context {public abstract PackageManager getPackageManager();public abstract String getPackageName();public abstract ApplicationInfo getApplicationInfo();public abstract void sendBroadcast(Intent intent);}
""",
    "android/content/Intent.java": """package android.content;import android.os.Bundle;import java.util.*;
public class Intent {public static final int FLAG_INCLUDE_STOPPED_PACKAGES=32;private String action,pkg;private int flags;private Map<String,Object> extras=new HashMap<>();public Intent(String a){action=a;}public Intent setPackage(String p){pkg=p;return this;}public String getPackage(){return pkg;}public String getAction(){return action;}public Intent addFlags(int f){flags|=f;return this;}public int getFlags(){return flags;}public Intent putExtra(String k,Bundle v){extras.put(k,v);return this;}public Intent putExtra(String k,String v){extras.put(k,v);return this;}public Intent putExtra(String k,int v){extras.put(k,v);return this;}public Bundle getBundleExtra(String k){return (Bundle)extras.get(k);}public String getStringExtra(String k){return (String)extras.get(k);}public int getIntExtra(String k,int d){return extras.containsKey(k)?(int)extras.get(k):d;}}
""",
    "android/content/pm/ApplicationInfo.java": "package android.content.pm;public class ApplicationInfo {public String packageName;public int uid;public boolean enabled;}",
    "android/content/pm/PackageManager.java": """package android.content.pm;import android.content.Intent;public abstract class PackageManager {public static class NameNotFoundException extends Exception {}public abstract ApplicationInfo getApplicationInfo(String name,int flags)throws NameNotFoundException;public abstract Intent getLaunchIntentForPackage(String name);}""",
    "android/os/IBinder.java": "package android.os;public interface IBinder {int FIRST_CALL_TRANSACTION=1,FLAG_ONEWAY=1;boolean pingBinder();boolean transact(int code,Parcel data,Parcel reply,int flags)throws RemoteException;}",
    "android/os/Binder.java": """package android.os;public class Binder implements IBinder {public static int callingUid;public boolean alive=true;public static int getCallingUid(){return callingUid;}public boolean pingBinder(){return alive;}public boolean transact(int c,Parcel d,Parcel r,int f)throws RemoteException{return onTransact(c,d,r,f);}protected boolean onTransact(int c,Parcel d,Parcel r,int f)throws RemoteException{return false;}}""",
    "android/os/Bundle.java": "package android.os;import java.util.*;public class Bundle {private Map<String,IBinder> binders=new HashMap<>();public void putBinder(String k,IBinder b){binders.put(k,b);}public IBinder getBinder(String k){return binders.get(k);}}",
    "android/os/Parcel.java": "package android.os;import java.util.*;public class Parcel {private List<Object> data=new ArrayList<>();private int at;public void writeStrongBinder(IBinder b){data.add(b);}public void writeString(String s){data.add(s);}public int dataAvail(){return data.size()-at;}public IBinder readStrongBinder(){if(at>=data.size())return null;return (IBinder)data.get(at++);}}",
    "android/os/RemoteException.java": "package android.os;public class RemoteException extends Exception {}",
    "android/os/Looper.java": "package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}",
    "android/os/Handler.java": """package android.os;import java.util.*;public class Handler {private static class Task {long when;Runnable run;Task(long w,Runnable r){when=w;run=r;}}private static List<Task> tasks=new ArrayList<>();private static long now;public Handler(Looper l){}public boolean post(Runnable r){tasks.add(new Task(now,r));return true;}public boolean postDelayed(Runnable r,long delay){tasks.add(new Task(now+delay,r));return true;}public void removeCallbacks(Runnable r){tasks.removeIf(t->t.run==r);}public static void reset(){tasks.clear();now=0;}public static void advance(long delta){now+=delta;flush();}public static void flush(){while(true){Task next=null;for(Task t:tasks)if(t.when<=now&&(next==null||t.when<next.when))next=t;if(next==null)return;tasks.remove(next);next.run.run();}}}""",
}


def main():
    with tempfile.TemporaryDirectory(prefix="shizuku-discovery-", dir=os.environ.get("TMPDIR")) as directory:
        work = Path(directory)
        sources = []
        for name, content in STUBS.items():
            target = work / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content)
            sources.append(str(target))
        sources += [str(ROOT / "app/src/main/java/com/termux/api/shizuku" / name)
                    for name in ("ShizukuManagers.java", "ShizukuBinderDiscovery.java")]
        sources.append(str(ROOT / "scripts/shizuku-discovery/ShizukuBinderDiscoveryHarness.java"))
        subprocess.run(["javac", "--release", "11", "-d", str(work), *sources], check=True)
        subprocess.run(["java", "-cp", str(work), "com.termux.api.shizuku.ShizukuBinderDiscoveryHarness"], check=True)


if __name__ == "__main__":
    main()
