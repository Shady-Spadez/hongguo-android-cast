package io.github.jackotom.hongguo.android;
import java.util.*;
public final class UpdatePolicyTest {
 static int checks;
 static void yes(boolean x){checks++;if(!x)throw new AssertionError("check "+checks);}
 static void bad(Runnable r){checks++;try{r.run();throw new AssertionError("accepted "+checks);}catch(IllegalArgumentException expected){}}
 static String url(String flavor){return "https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.11/Hongguo-0.12.11-"+flavor+"-release-compatible.apk";}
 static void validate(String pkg,String flavor,int code,String name,int sdk,long size,String hash,String url,int installed,String installedName){UpdatePolicy.validate(1,"android-compatible",flavor,pkg,code,name,sdk,size,hash,url,pkg,installed,installedName,35);}
 public static void main(String[]a){
  String pkg="io.github.jackotom.hongguo.android",hash="a".repeat(64);
  validate(pkg,"phone",34,"0.12.11",26,100,hash,url("phone"),33,"0.12.10"); checks++;
  validate(pkg+".tv","tv",34,"0.12.11",26,100,hash,url("tv"),33,"0.12.10"); checks++;
  validate(pkg,"phone",33,"0.12.10",26,100,hash,url("phone").replace("0.12.11","0.12.10"),33,"0.12.10"); checks++;
  bad(()->validate(pkg,"tv",34,"0.12.11",26,100,hash,url("tv"),33,"0.12.10"));
  bad(()->validate(pkg,"phone",32,"0.12.11",26,100,hash,url("phone"),33,"0.12.10"));
  bad(()->validate(pkg,"phone",33,"0.12.11",26,100,hash,url("phone"),33,"0.12.10"));
  bad(()->validate(pkg,"phone",34,"0.12.11",36,100,hash,url("phone"),33,"0.12.10"));
  for(String u: new String[]{url("tv"),url("phone")+"?q=1",url("phone")+"#x",url("phone").replace("github.com","github.com.evil.test"),url("phone").replace("https:","http:"),url("phone").replace("skydeadpeng","aston314"),url("phone").replace("/v0.12.11/","/latest/"),"https://github.com@evil.test/a.apk",url("phone").replace("github.com/","github.com:443/")})bad(()->validate(pkg,"phone",34,"0.12.11",26,100,hash,u,33,"0.12.10"));
  for(long size:new long[]{0,-1,UpdatePolicy.MAX_APK_BYTES+1})bad(()->validate(pkg,"phone",34,"0.12.11",26,size,hash,url("phone"),33,"0.12.10"));
  for(String h:new String[]{"", "a".repeat(63),"z".repeat(64)})bad(()->validate(pkg,"phone",34,"0.12.11",26,100,h,url("phone"),33,"0.12.10"));
  bad(()->UpdatePolicy.validate(2,"android-compatible","phone",pkg,34,"0.12.11",26,100,hash,url("phone"),pkg,33,"0.12.10",35));
  bad(()->UpdatePolicy.validate(1,"other","phone",pkg,34,"0.12.11",26,100,hash,url("phone"),pkg,33,"0.12.10",35));
  yes(UpdatePolicy.automaticCheckDue(1000,0));yes(UpdatePolicy.automaticCheckDue(1000,2000));yes(!UpdatePolicy.automaticCheckDue(1000,999));yes(UpdatePolicy.automaticCheckDue(UpdatePolicy.CHECK_INTERVAL+1,1));
  yes(UpdatePolicy.matchingArchive(pkg,33,pkg,34,"0.12.11",34,"0.12.11",new String[]{"b","a"},new String[]{"a","b"}));
  yes(!UpdatePolicy.matchingArchive(pkg,33,pkg,34,"0.12.11",34,"0.12.11",new String[]{"a"},new String[]{"b"}));
  yes(!UpdatePolicy.matchingArchive(pkg,33,pkg+".tv",34,"0.12.11",34,"0.12.11",new String[]{"a"},new String[]{"a"}));
  yes(!UpdatePolicy.matchingArchive(pkg,33,pkg,33,"0.12.11",34,"0.12.11",new String[]{"a"},new String[]{"a"}));
  yes(!UpdatePolicy.matchingArchive(pkg,33,pkg,34,"0.12.11",34,"0.12.11",new String[0],new String[0]));
  yes(!UpdatePolicy.matchingArchive(pkg,33,pkg,34,"0.12.12",34,"0.12.11",new String[]{"a"},new String[]{"a"}));
  yes(UpdatePolicy.allowedRedirect("https://release-assets.githubusercontent.com/github-production-release-asset/abc?token=abc"));yes(!UpdatePolicy.allowedRedirect("https://127.0.0.1/x"));yes(!UpdatePolicy.allowedRedirect("http://github.com/x"));yes(!UpdatePolicy.allowedRedirect("https://github.com.evil.test/x"));yes(!UpdatePolicy.allowedRedirect("https://user@github.com/x"));
  System.out.println("UpdatePolicy: "+checks+" checks passed");
 }
}
