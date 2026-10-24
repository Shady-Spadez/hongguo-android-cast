package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Account traffic has its own bounded serial worker and never uses video/cast executors. */
final class AccountSync {
    private static AccountSync instance;
    static synchronized AccountSync init(Context context) {
        if(instance==null)instance=new AccountSync(context.getApplicationContext());return instance;
    }
    static synchronized AccountSync get() { if(instance==null)throw new IllegalStateException("账号服务尚未初始化");return instance; }
    private final Object lock=new Object();
    private final SharedPreferences preferences,guestPreferences;
    private final AccountTokenVault vault=new AccountTokenVault();
    private final ScheduledThreadPoolExecutor executor=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"hongguo-account-sync");t.setDaemon(true);return t;});
    private final Map<String,HistorySyncState> states=new HashMap<>();
    private final ScheduledThreadPoolExecutor diskExecutor=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"hongguo-account-store");t.setDaemon(true);return t;});
    private final Set<String> dirtyScopes=new HashSet<>();
    private ScheduledFuture<?> diskTask;
    private final String deviceId;
    private String origin="",userId="",username="",scope="",token="",expiresAt="",error="",localError="";
    private boolean loginRequired,syncing,loginPending;
    private long generation,pendingLoginGeneration,lastSyncAt,nextAllowedAt,lastAttemptAt,loginAllowedAt;
    private int failures;
    private ScheduledFuture<?> scheduled;
    private volatile Runnable listener;
    private AccountSync(Context context) {
        preferences=context.getSharedPreferences("hongguo_accounts_v1",Context.MODE_PRIVATE);
        guestPreferences=context.getSharedPreferences("hongguo_library_v1",Context.MODE_PRIVATE);
        executor.setRemoveOnCancelPolicy(true);
        String stored=preferences.getString("deviceId","");
        if(!stored.matches("[a-zA-Z0-9_.:-]{8,80}")){stored=UUID.randomUUID().toString();preferences.edit().putString("deviceId",stored).commit();}deviceId=stored;
        try {
            origin=preferences.getString("origin","");userId=preferences.getString("userId","");username=preferences.getString("username","");expiresAt=preferences.getString("expiresAt","");
            if(!origin.isEmpty()&&!userId.isEmpty()) {
                origin=AccountEndpoint.normalize(origin);scope=scope(origin,userId);state(scope);
                String sealed=preferences.getString("sealedToken","");
                if(!sealed.isEmpty())try{token=vault.open(sealed,scope);}catch(Exception ignored){error="登录凭据需要重新验证";}
                loginRequired=token.isEmpty();
            } else {if(!origin.isEmpty())origin=AccountEndpoint.normalize(origin);userId="";username="";scope="";}
        }catch(Exception ignored){token="";scope="";userId="";username="";origin="";error="账号数据读取未成功，请重新登录";}
        LibraryStore.setHistoryFactory(this::openHistory);
    }
    synchronized void setListener(Runnable listener) { this.listener=listener; }
    synchronized void clearListener(Runnable expected) { if(listener==expected)listener=null; }
    private void notifyChanged() { Runnable current=listener;if(current!=null)try{current.run();}catch(RuntimeException ignored){} }
    private static String scope(String origin,String userId) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest((origin+"\n"+userId).getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();for(byte b:digest)result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();
    }
    private String wireDeviceId(String accountScope) throws Exception { return scope(deviceId,accountScope); }
    private HistorySyncState state(String key) throws JSONException {
        HistorySyncState state=states.get(key);if(state==null){state=new HistorySyncState(preferences.getString("history_"+key,"{}"));states.put(key,state);}return state;
    }
    private void persist(String key,HistorySyncState state) throws JSONException {
        String storage="history_"+key,old=preferences.getString(storage,null);boolean committed=false;
        try {committed=preferences.edit().putString(storage,state.encode()).commit();}
        finally {if(!committed){SharedPreferences.Editor restore=preferences.edit();if(old==null)restore.remove(storage);else restore.putString(storage,old);restore.commit();}}
        if(!committed)throw new JSONException("观看记录保存未成功");
    }
    private interface StateUpdate { void run(HistorySyncState state) throws Exception; }
    private void update(String key,StateUpdate action) throws Exception {
        HistorySyncState old=state(key).copyState();
        try{HistorySyncState current=state(key);action.run(current);persist(key,current);}
        catch(Exception error){states.put(key,old);throw error;}
    }
    private void persistSoonLocked(String key) {
        dirtyScopes.add(key);
        if(diskTask==null)diskTask=diskExecutor.schedule(this::persistDirty,250,TimeUnit.MILLISECONDS);
    }
    private void persistDirty() {
        synchronized(lock) {
            diskTask=null;List<String> keys=new ArrayList<>(dirtyScopes);
            for(String key:keys)try{persist(key,state(key));dirtyScopes.remove(key);}catch(Exception failure){error="本机观看记录保存未成功，请检查存储空间";}
            // One bounded retry, never a per-frame disk queue. Foreground/flush and the next tick also retry.
            if(!dirtyScopes.isEmpty())diskTask=diskExecutor.schedule(this::persistDirty,15000,TimeUnit.MILLISECONDS);
        }
    }
    private LibraryStore.HistoryAccess openHistory() {
        synchronized(lock) {
            if(scope.isEmpty())return null;
            final String captured=scope,writer=UUID.randomUUID().toString();
            return new LibraryStore.HistoryAccess() {
                public JSONArray rows() throws JSONException { synchronized(lock){return state(captured).rows();} }
                public void record(JSONObject row) throws JSONException {
                    synchronized(lock) {
                        HistorySyncState s=state(captured);boolean recorded;
                        try{recorded=s.record(writer,row);}catch(JSONException invalid){if(captured.equals(scope))localError=invalid.getMessage();throw invalid;}
                        if(recorded){persistSoonLocked(captured);if(captured.equals(scope))localError="";}else if(s.conflicted()&&captured.equals(scope))error="其他设备已更新进度，当前旧播放会话已停止上传；重新开启播放或投屏会话后可继续同步";
                        if(captured.equals(scope))scheduleLocked(false);
                    }
                }
            };
        }
    }
    JSONObject getAccount() throws JSONException {
        synchronized(lock) {
            JSONObject user=userId.isEmpty()?null:new JSONObject().put("id",userId).put("username",username);
            return new JSONObject().put("serverUrl",origin).put("username",username).put("userId",userId).put("user",user==null?JSONObject.NULL:user)
                .put("authenticated",!token.isEmpty()).put("loggedIn",!token.isEmpty()).put("loginRequired",loginRequired).put("syncing",syncing||loginPending)
                .put("pendingCount",scope.isEmpty()?0:state(scope).pendingCount()).put("lastSyncAt",lastSyncAt).put("error",localError.isEmpty()?error:localError);
        }
    }
    JSONObject login(JSONObject params) throws Exception {
        final String endpoint=AccountEndpoint.normalize(params.optString("serverUrl","")),name=params.optString("username","").trim(),password=params.optString("password","");
        if(name.isEmpty()||name.length()>32||password.isEmpty()||password.getBytes(StandardCharsets.UTF_8).length>72)throw new IOException("请检查用户名和密码");
        final String device=params.optString("deviceName","Android");if(device.length()>80)throw new IOException("设备名称过长");
        final long requestGeneration;
        synchronized(lock) {if(loginPending)throw new IOException("登录处理中，请稍候");if(System.currentTimeMillis()<loginAllowedAt)throw new IOException("请求较频繁，请稍后再试");loginPending=true;requestGeneration=++generation;pendingLoginGeneration=requestGeneration;if(scheduled!=null){scheduled.cancel(false);scheduled=null;}}
        Future<JSONObject> future=executor.submit(()->{
            try {
                JSONObject payload=new JSONObject().put("username",name).put("password",password).put("deviceName",device);
                JSONObject response=request(endpoint,"/v1/auth/login","POST","",payload,System.currentTimeMillis()+20000);
                JSONObject user=response.getJSONObject("user");String id=user.getString("id"),bearer=response.getString("token");
                if(!id.matches("[a-zA-Z0-9_-]{1,128}")||!bearer.matches("[a-zA-Z0-9_-]{32,512}"))throw new IOException("登录响应格式异常");
                String selectedScope=scope(endpoint,id),sealed=vault.seal(bearer,selectedScope);
                synchronized(lock) {
                    if(generation!=requestGeneration)throw new IOException("登录已取消");
                    state(selectedScope);
                    Map<String,?> previous=preferences.getAll();boolean saved=false;
                    try{saved=preferences.edit().putString("origin",endpoint).putString("userId",id).putString("username",user.getString("username")).putString("sealedToken",sealed).putString("expiresAt",response.optString("expiresAt","")).commit();}
                    finally{if(!saved){SharedPreferences.Editor restore=preferences.edit();for(String key:new String[]{"origin","userId","username","sealedToken","expiresAt"}){Object value=previous.get(key);if(value instanceof String)restore.putString(key,(String)value);else restore.remove(key);}restore.commit();}}
                    if(!saved)throw new IOException("登录信息保存未成功");
                    origin=endpoint;userId=id;username=user.getString("username");scope=selectedScope;token=bearer;expiresAt=response.optString("expiresAt","");loginRequired=false;error="";localError="";failures=0;nextAllowedAt=0;loginAllowedAt=0;lastSyncAt=0;
                }
                notifyChanged();return getAccount();
            } catch(Exception exception) {
                synchronized(lock){if(generation==requestGeneration){error=message(exception);if(exception instanceof HttpFailure&&((HttpFailure)exception).status==429)loginAllowedAt=System.currentTimeMillis()+((HttpFailure)exception).retryMillis;}}
                notifyChanged();throw exception;
            } finally {synchronized(lock){if(pendingLoginGeneration==requestGeneration){loginPending=false;scheduleLocked(true);}}notifyChanged();}
        });
        try{return future.get(45,TimeUnit.SECONDS);}catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IOException("登录未成功");}
        catch(TimeoutException e){synchronized(lock){if(generation==requestGeneration)generation++;if(pendingLoginGeneration==requestGeneration)loginPending=false;}future.cancel(true);throw new IOException("登录等待超时，请重试");}
    }
    JSONObject logout() throws Exception {
        final String previousOrigin,previousToken,previousDeviceId;final JSONArray pendingBatch;
        synchronized(lock) {
            previousOrigin=origin;previousToken=token;previousDeviceId=scope.isEmpty()?"":wireDeviceId(scope);pendingBatch=scope.isEmpty()?new JSONArray():state(scope).batch(10);if(!scope.isEmpty())persist(scope,state(scope));
            Map<String,?> old=preferences.getAll();boolean committed=false;
            try {committed=preferences.edit().remove("userId").remove("username").remove("sealedToken").remove("expiresAt").commit();}
            finally {if(!committed){SharedPreferences.Editor restore=preferences.edit();for(String key:new String[]{"userId","username","sealedToken","expiresAt"}){Object value=old.get(key);if(value instanceof String)restore.putString(key,(String)value);else restore.remove(key);}restore.commit();}}
            if(!committed)throw new IOException("退出信息保存未成功，请重试");
            generation++;loginPending=false;if(scheduled!=null){scheduled.cancel(false);scheduled=null;}
            scope="";userId="";username="";token="";expiresAt="";loginRequired=false;syncing=false;error="";localError="";lastSyncAt=0;failures=0;nextAllowedAt=0;
        }
        if(!previousToken.isEmpty())executor.execute(()->{
            long deadline=System.currentTimeMillis()+12000;
            // Keep the outbox until a later authenticated replay acknowledges it. Logout responses never mutate local state.
            if(pendingBatch.length()>0)try{request(previousOrigin,"/v1/history/sync","POST",previousToken,new JSONObject().put("deviceId",previousDeviceId).put("mutations",pendingBatch),deadline-4000);}catch(Exception ignored){}
            try{request(previousOrigin,"/v1/auth/logout","POST",previousToken,new JSONObject(),deadline);}catch(Exception ignored){}
        });
        notifyChanged();return getAccount();
    }
    JSONObject importGuestHistory() throws Exception {
        int imported=0,skipped=0;
        synchronized(lock) {
            if(token.isEmpty()||scope.isEmpty())throw new IOException("请先登录账号");
            JSONArray rows=new JSONArray(guestPreferences.getString("history","[]"));HistorySyncState before=state(scope).copyState();
            try {
                for(int i=Math.min(100,rows.length())-1;i>=0;i--) {
                    try {
                        JSONObject old=rows.getJSONObject(i),series=LibraryStore.series(old),episode=LibraryStore.episode(old);String sid=series.getString("seriesId");
                        if(state(scope).contains(sid)){skipped++;continue;}
                        long position=old.optLong("positionMs");if(position<0||position>604800000L){skipped++;continue;}
                        JSONObject row=series.put("vid",episode.getString("vid")).put("index",episode.getInt("index")).put("positionMs",position).put("updatedAt",old.optLong("updatedAt"));
                        if(state(scope).record("import-"+UUID.randomUUID(),row))imported++;else skipped++;
                    }catch(JSONException invalid){skipped++;}
                }
                persist(scope,state(scope));scheduleLocked(true);
            }catch(Exception failure){states.put(scope,before);throw failure;}
        }
        notifyChanged();return getAccount().put("importedCount",imported).put("skippedCount",skipped);
    }
    JSONObject syncNow() throws JSONException { synchronized(lock){scheduleLocked(true);}notifyChanged();return getAccount(); }
    void foreground() { synchronized(lock){scheduleLocked(true);} }
    void flush() { synchronized(lock){if(diskTask!=null){diskTask.cancel(false);diskTask=null;}if(!dirtyScopes.isEmpty())diskTask=diskExecutor.schedule(this::persistDirty,0,TimeUnit.MILLISECONDS);scheduleLocked(true);} }
    private void scheduleLocked(boolean immediate) {
        if(token.isEmpty()||scope.isEmpty()||syncing||loginPending)return;
        long now=System.currentTimeMillis(),delay=Math.max(0,nextAllowedAt-now);
        if(!immediate)delay=Math.max(delay,15000-Math.max(0,now-lastAttemptAt));
        if(scheduled!=null){if(scheduled.getDelay(TimeUnit.MILLISECONDS)<=delay)return;scheduled.cancel(false);}
        scheduled=executor.schedule(()->{synchronized(lock){scheduled=null;}sync();},delay,TimeUnit.MILLISECONDS);
    }
    private void sync() {
        final String currentScope,currentOrigin,currentToken;final long epoch,deadline=System.currentTimeMillis()+20000;
        synchronized(lock){if(token.isEmpty()||scope.isEmpty()||syncing)return;syncing=true;epoch=generation;currentScope=scope;currentOrigin=origin;currentToken=token;lastAttemptAt=System.currentTimeMillis();}
        notifyChanged();boolean more=false;
        try {
            JSONArray batch;JSONObject writers;
            synchronized(lock){HistorySyncState state=state(currentScope);batch=state.batch(10);writers=state.sentWriters(batch);persist(currentScope,state);}
            if(batch.length()>0) {
                JSONObject response=request(currentOrigin,"/v1/history/sync","POST",currentToken,new JSONObject().put("deviceId",wireDeviceId(currentScope)).put("mutations",batch),deadline);
                synchronized(lock){if(!current(epoch,currentScope))return;update(currentScope,state->state.acknowledge(response.getJSONArray("results"),writers));}
            }
            for(int page=0;page<4;page++) {
                long cursor;synchronized(lock){if(!current(epoch,currentScope))return;cursor=state(currentScope).cursor();}
                JSONObject response=request(currentOrigin,"/v1/history?cursor="+cursor+"&limit=50","GET",currentToken,null,deadline);
                synchronized(lock){if(!current(epoch,currentScope))return;long next=response.getLong("cursor");more=response.optBoolean("hasMore");if(more&&next<=cursor)throw new IOException("同步游标未推进");update(currentScope,state->state.pull(response.getJSONArray("records"),next));}
                if(!more)break;
            }
            synchronized(lock){if(current(epoch,currentScope)){lastSyncAt=System.currentTimeMillis();error=state(currentScope).conflicted()?"其他设备已更新进度，已保留云端记录；重新开启播放或投屏会话后可继续同步":"";failures=0;nextAllowedAt=0;more=more||state(currentScope).pendingCount()>0;}}
        } catch(Exception exception) {
            synchronized(lock){if(current(epoch,currentScope)) {
                error=message(exception);failures=Math.min(6,failures+1);
                long delay=Math.min(300000,15000L*(1L<<Math.min(4,failures-1)));
                if(exception instanceof HttpFailure) {
                    HttpFailure http=(HttpFailure)exception;delay=Math.max(delay,http.retryMillis);
                    if(http.status==401){token="";loginRequired=true;preferences.edit().remove("sealedToken").commit();error="登录已失效，本地记录已保留，请重新登录";}
                }
                nextAllowedAt=System.currentTimeMillis()+delay;more=false;
            }}
        } finally {
            synchronized(lock){syncing=false;if(current(epoch,currentScope))scheduleLocked(false);}
            notifyChanged();
        }
    }
    private boolean current(long epoch,String expectedScope) { return generation==epoch&&scope.equals(expectedScope); }
    private static String message(Exception error) {
        if(error instanceof HttpFailure)return error.getMessage();
        if("观看记录保存未成功".equals(error.getMessage())||"登录信息保存未成功".equals(error.getMessage())||"退出信息保存未成功，请重试".equals(error.getMessage()))return "本机信息保存未成功，请检查存储空间";
        if(error instanceof java.net.SocketTimeoutException||error instanceof TimeoutException)return "服务器响应超时，本地记录已保留";
        if(error instanceof javax.net.ssl.SSLException)return "服务器 HTTPS 证书验证未通过";
        if(error instanceof JSONException)return "服务器同步数据格式异常，本地记录已保留";
        return "账号服务器连接未成功，本地记录已保留";
    }
    private static final class HttpFailure extends IOException {
        final int status;final long retryMillis;
        HttpFailure(int status,long retryMillis,String code){super(status==401?"账号或登录凭据无效":status==429?"请求较频繁，请稍后重试":status==404?"服务器需更新到支持观看记录同步的版本":status==409?"服务器记录同步出现冲突，请稍后同步":"账号服务响应 HTTP "+status);this.status=status;this.retryMillis=retryMillis;}
    }
    private static JSONObject request(String origin,String path,String method,String token,JSONObject body,long deadline) throws Exception {
        int remaining=(int)Math.min(10000,deadline-System.currentTimeMillis());if(remaining<=0)throw new java.net.SocketTimeoutException();
        HttpsURLConnection connection=(HttpsURLConnection)new URL(origin+path).openConnection();
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(Math.min(8000,remaining));connection.setReadTimeout(remaining);connection.setRequestMethod(method);connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Accept-Encoding","identity");connection.setRequestProperty("User-Agent","Hongguo-Account/1");
        if(!token.isEmpty())connection.setRequestProperty("Authorization","Bearer "+token);
        try {
            if(body!=null){byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>1024*1024)throw new IOException("同步请求过大");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(bytes.length);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");try(OutputStream output=connection.getOutputStream()){output.write(bytes);}}
            int status=connection.getResponseCode();long retry=0;
            try{retry=Math.min(300000,Math.max(1000,Long.parseLong(connection.getHeaderField("Retry-After"))*1000));}catch(Exception ignored){}
            if(status<200||status>=300)throw new HttpFailure(status,retry,"");
            if(status==204)return new JSONObject();
            long length=connection.getContentLengthLong();if(length>1024*1024)throw new IOException("同步响应过大");
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                byte[] block=new byte[8192];int count;while((count=input.read(block))!=-1){if(System.currentTimeMillis()>deadline||Thread.currentThread().isInterrupted())throw new java.net.SocketTimeoutException();if(output.size()+count>1024*1024)throw new IOException("同步响应过大");output.write(block,0,count);}
                return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            }
        }finally{connection.disconnect();}
    }
}
