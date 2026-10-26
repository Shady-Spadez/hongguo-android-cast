package io.github.jackotom.hongguo.android;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Android's own release identity. Never consumes the desktop or reference project's feed. */
final class UpdatePolicy {
    static final String REPOSITORY = "skydeadpeng/hongguo-android-cast";
    static final long CHECK_INTERVAL = 24L * 60 * 60 * 1000;
    static final long MAX_APK_BYTES = 150L * 1024 * 1024;
    private static final Set<String> ASSET_HOSTS = new HashSet<>(Arrays.asList("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com"));
    static String manifestUrl(boolean tv) { return "https://raw.githubusercontent.com/" + REPOSITORY + "/main/android/update-" + (tv ? "tv" : "phone") + ".json"; }
    static boolean automaticCheckDue(long now, long attempted) { return attempted <= 0 || attempted > now || now - attempted >= CHECK_INTERVAL; }
    static void validate(int schema, String channel, String flavor, String pkg, int code, String name,
                         int minSdk, long size, String sha256, String download, String installedPackage,
                         int installedCode, String installedName, int deviceSdk) {
        String expected = installedPackage.endsWith(".tv") ? "tv" : "phone";
        if (schema != 1 || !"android-compatible".equals(channel) || !expected.equals(flavor)
                || !installedPackage.equals(pkg) || !("io.github.jackotom.hongguo.android" + (expected.equals("tv") ? ".tv" : "")).equals(pkg)
                || code <= 0 || name == null || !name.matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                || minSdk < 26 || minSdk > deviceSdk || size <= 0 || size > MAX_APK_BYTES
                || sha256 == null || !sha256.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException("更新信息与当前设备不匹配");
        if (code < installedCode || code == installedCode && !installedName.equals(name)) throw new IllegalArgumentException("更新清单尚未刷新或版本不一致，请稍后重试");
        URI uri;
        try { uri = URI.create(download); } catch (RuntimeException invalid) { throw new IllegalArgumentException("更新包地址无效"); }
        String prefix = "/" + REPOSITORY + "/releases/download/v" + name + "/Hongguo-" + name + "-" + flavor;
        if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost()) || uri.getPort() != -1
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !((prefix + "-release-compatible.apk").equals(uri.getRawPath()) || (prefix + "-release.apk").equals(uri.getRawPath()))) throw new IllegalArgumentException("更新包不是本应用的固定版本发行文件");
    }
    static boolean matchingArchive(String installedPackage, int installedCode, String archivePackage,
                                   int archiveCode, String archiveName, int expectedCode, String expectedName,
                                   String[] installedSigners, String[] archiveSigners) {
        if (!installedPackage.equals(archivePackage) || archiveCode <= installedCode || archiveCode != expectedCode
                || !expectedName.equals(archiveName) || installedSigners == null || archiveSigners == null
                || installedSigners.length == 0 || installedSigners.length != archiveSigners.length) return false;
        String[] left = installedSigners.clone(), right = archiveSigners.clone(); Arrays.sort(left); Arrays.sort(right);
        return Arrays.equals(left, right);
    }
    static boolean allowedRedirect(String value) {
        try { URI uri = URI.create(value); return "https".equals(uri.getScheme()) && ASSET_HOSTS.contains(uri.getHost()) && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getRawUserInfo() == null && uri.getRawFragment() == null; }
        catch (RuntimeException invalid) { return false; }
    }
}
