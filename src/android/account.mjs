/** UI-only account projection. Credentials and tokens belong to the native layer. */
export function accountView(value) {
  if (!value || typeof value !== 'object' || typeof value.authenticated !== 'boolean') {
    throw new Error('账号状态读取失败，请重试。');
  }
  const integer = n => Number.isSafeInteger(n) && n >= 0 ? n : 0;
  const text = value => typeof value === 'string' ? value : '';
  return {
    authenticated: value.authenticated,
    serverUrl: text(value.serverUrl),
    user: value.user && typeof value.user === 'object' ? { id: text(value.user.id), username: text(value.user.username) } : null,
    syncing: value.syncing === true,
    lastSyncAt: integer(value.lastSyncAt), pendingCount: integer(value.pendingCount),
    error: text(value.error), loginRequired: value.loginRequired === true,
  };
}

export function loginFields({ serverUrl, username, password, deviceName }) {
  const address = String(serverUrl || '').trim();
  let parsed;
  try { parsed = new URL(address); } catch (_) { throw new Error('请填写完整的服务器地址，例如 https://sync.example.com。'); }
  if (parsed.protocol !== 'https:' || !parsed.hostname || parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new Error('服务器地址须使用 HTTPS，不含账号、密码、查询参数或片段。');
  }
  if (parsed.pathname !== '/') throw new Error('请填写服务器根地址，不要附加接口或服务路径。');
  if (address.length > 2048) throw new Error('服务器地址过长，请检查。');
  const name = String(username || '').trim();
  if (!name || name.length > 64) throw new Error('请填写账号，最多 64 个字符。');
  if (typeof password !== 'string' || !password.length || password.length > 256) throw new Error('请填写密码，最多 256 个字符。');
  return { serverUrl: address.replace(/\/+$/, ''), username: name, password, deviceName };
}

export function accountStatus(account) {
  if (!account) return '正在读取账号状态…';
  if (account.loginRequired) return '登录已过期，请重新登录；本机进度继续保留。';
  if (!account.authenticated) return '游客模式 · 观看记录仅保存在这台设备';
  if (account.syncing) return '正在同步观看记录和播放进度…';
  if (account.error) return '同步暂未完成 · 本机进度已保留，可稍后重试';
  if (account.pendingCount) return `有 ${account.pendingCount} 条进度待同步`;
  if (account.lastSyncAt) return `最近同步：${new Date(account.lastSyncAt).toLocaleString('zh-CN', { hour12: false })}`;
  return '已登录 · 等待首次同步';
}
