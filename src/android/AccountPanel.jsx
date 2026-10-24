import React, { useCallback, useEffect, useRef, useState } from 'react';
import { nativeCall } from './bridge.mjs';
import { accountStatus, accountView, loginFields } from './account.mjs';

const message = error => error?.message || '这次操作未完成，请重试。';
const signal = name => window.dispatchEvent(new Event(name));

export default function AccountPanel({ isTv, focusRequest }) {
  const [account, setAccount] = useState(null);
  const [readError, setReadError] = useState('');
  const [actionError, setActionError] = useState('');
  const [actionNotice, setActionNotice] = useState('');
  const [action, setAction] = useState('');
  const [serverUrl, setServerUrl] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirmLogout, setConfirmLogout] = useState(false);
  const [confirmImport, setConfirmImport] = useState(false);
  const mounted = useRef(false), epoch = useRef(0), actionRef = useRef('');
  const serverEdited = useRef(false), nameEdited = useRef(false);
  const section = useRef(null), form = useRef(null), logoutButton = useRef(null), cancelButton = useRef(null), syncButton = useRef(null), importButton = useRef(null), importCancel = useRef(null);
  const accept = useCallback(raw => {
    const next = accountView(raw);
    setAccount(next); setReadError('');
    if (!serverEdited.current) setServerUrl(next.serverUrl);
    if (!nameEdited.current) setUsername(next.user?.username || '');
  }, []);
  const refresh = useCallback(async () => {
    if (actionRef.current) return;
    const read = ++epoch.current;
    try {
      const raw = await nativeCall('getAccount');
      if (mounted.current && epoch.current === read) accept(raw);
    } catch (error) {
      if (mounted.current && epoch.current === read) setReadError(message(error));
    }
  }, [accept]);
  useEffect(() => {
    mounted.current = true; refresh();
    const visible = () => { if (document.visibilityState !== 'hidden') refresh(); };
    const timer = setInterval(visible, 5000);
    window.addEventListener('androidaccountchanged', refresh);
    window.addEventListener('androidresume', refresh);
    document.addEventListener('visibilitychange', visible);
    return () => {
      mounted.current = false; epoch.current++;
      clearInterval(timer); window.removeEventListener('androidaccountchanged', refresh);
      window.removeEventListener('androidresume', refresh); document.removeEventListener('visibilitychange', visible);
      // React owns the password only; unmount drops the form and its state.
      if (form.current) form.current.reset();
    };
  }, [refresh]);
  useEffect(() => {
    if (!focusRequest) return;
    const frame = requestAnimationFrame(() => (syncButton.current || form.current?.querySelector('input'))?.focus());
    return () => cancelAnimationFrame(frame);
  }, [focusRequest]);
  useEffect(() => { if (confirmLogout) cancelButton.current?.focus(); }, [confirmLogout]);
  useEffect(() => { if (confirmImport) importCancel.current?.focus(); }, [confirmImport]);
  const perform = async (method, params = {}) => {
    if (actionRef.current) return;
    const identityChange = method === 'accountLogin' || method === 'accountLogout';
    actionRef.current = method; epoch.current++; setAction(method); setActionError(''); setActionNotice('');
    if (identityChange) signal('androidaccountchanging');
    try {
      const raw = await nativeCall(method, params, 90000);
      if (mounted.current) {
        if (identityChange) { serverEdited.current = false; nameEdited.current = false; }
        accept(raw); setConfirmLogout(false); setConfirmImport(false);
        if (method === 'importGuestHistory') {
          const validCount = n => Number.isSafeInteger(n) && n >= 0;
          setActionNotice(validCount(raw.importedCount) && validCount(raw.skippedCount)
            ? `已补充 ${raw.importedCount} 部剧，跳过 ${raw.skippedCount} 条已有或无效记录。本机游客记录保留，完成同步后其他设备可见。`
            : '导入已完成，本机游客记录保留，完成同步后其他设备可见。');
        }
      }
    } catch (error) { if (mounted.current) setActionError(message(error)); }
    finally {
      actionRef.current = '';
      // Always settle even if the user left About during a network operation.
      if (identityChange) signal('androidaccountsettled'); else signal('androidaccountchanged');
      if (mounted.current) {
        setAction(''); await refresh();
        requestAnimationFrame(() => {
          if (!mounted.current) return;
          if (method === 'importGuestHistory' && document.activeElement === document.body) importButton.current?.focus();
          if (identityChange && (document.activeElement === document.body || section.current?.contains(document.activeElement))) {
            (syncButton.current || form.current?.querySelector('input'))?.focus({ preventScroll: true });
            section.current?.scrollIntoView({ block: 'start' });
          }
        });
      }
    }
  };
  const login = event => {
    event.preventDefault();
    let fields;
    try { fields = loginFields({ serverUrl, username, password, deviceName: isTv ? 'Android TV' : 'Android 手机' }); }
    catch (error) { setActionError(message(error)); setPassword(''); return; }
    // Clear the visible password immediately; never write it to web storage.
    setPassword(''); perform('accountLogin', fields);
  };
  const cancelLogout = () => { setConfirmLogout(false); logoutButton.current?.focus(); };
  const cancelImport = () => { setConfirmImport(false); importButton.current?.focus(); };
  const loggedIn = account?.authenticated === true;
  const busy = !!action;
  const visibleError = actionError || readError || account?.error;
  return <section ref={section} className="about-section account-section" aria-labelledby="account-title" aria-busy={busy || account?.syncing || false}>
    <h2 id="account-title">账号与同步</h2>
    <p className="account-status" role="status">{action === 'accountLogin' ? '正在登录…' : action === 'accountLogout' ? '正在退出…' : action === 'syncHistory' ? '正在同步观看记录和播放进度…' : action === 'importGuestHistory' ? '正在导入本机游客记录…' : accountStatus(account)}</p>
    <p className="account-note">手机和电视填写同一个服务器地址，登录同一个账号，即可同步观看记录和播放进度。收藏、搜索历史与视频缓存保留在本机。</p>
    {visibleError && <div className="account-error" role="alert"><p>{visibleError}</p>{!loggedIn && <button type="button" className="button" disabled={busy} onClick={refresh}>重新读取状态</button>}</div>}
    {actionNotice && <p className="account-note" role="status">{actionNotice}</p>}
    {loggedIn ? <>
      <dl className="account-details"><div><dt>当前账号</dt><dd>{account.user?.username || '已登录'}</dd></div><div><dt>服务器</dt><dd>{account.serverUrl}</dd></div><div><dt>待同步进度</dt><dd>{account.pendingCount} 条</dd></div></dl>
      <p className="account-note">网络暂时不通时继续在本机记录，恢复连接后自动重试；可以随时手动同步。退出后保留独立的账号记录，页面显示本机游客历史。</p>
      <div className="account-actions"><button ref={syncButton} type="button" className="button primary" disabled={busy || account.syncing} onClick={() => perform('syncHistory')}>{busy || account.syncing ? '同步中…' : account.error ? '重试同步' : '立即同步'}</button><button ref={logoutButton} type="button" className="button" disabled={busy} aria-expanded={confirmLogout} aria-controls="account-logout-confirm" onClick={() => { setConfirmImport(false); setConfirmLogout(true); }}>退出账号</button><button ref={importButton} type="button" className="button" disabled={busy || account.syncing} aria-expanded={confirmImport} aria-controls="account-import-confirm" onClick={() => { setConfirmLogout(false); setConfirmImport(true); }}>导入本机游客记录</button></div>
      {confirmImport && <div id="account-import-confirm" className="account-confirm" role="group" aria-label="确认导入本机游客记录"><p>只补充此账号没有的剧，不覆盖云端进度；本机原记录保留。确认将这台设备的游客观看记录导入当前账号？</p><div className="account-actions"><button ref={importCancel} type="button" className="button" disabled={busy} onClick={cancelImport}>取消</button><button type="button" className="button" disabled={busy || account.syncing} onClick={() => perform('importGuestHistory')}>确认导入</button></div></div>}
      {confirmLogout && <div id="account-logout-confirm" className="account-confirm" role="group" aria-label="确认退出账号"><p>{account.pendingCount ? `还有 ${account.pendingCount} 条进度待同步。` : ''}退出后显示游客历史；当前账号在本机的记录仍保留，重新登录后继续同步。</p><div className="account-actions"><button ref={cancelButton} type="button" className="button" disabled={busy} onClick={cancelLogout}>取消</button><button type="button" className="button" disabled={busy} onClick={() => perform('accountLogout')}>确认退出</button></div></div>}
    </> : <form ref={form} className="account-form" onSubmit={login}>
      <label htmlFor="account-server">服务器地址<input id="account-server" name="server" type="url" inputMode="url" autoCapitalize="none" autoCorrect="off" spellCheck={false} autoComplete="off" placeholder="https://sync.example.com" maxLength={2048} value={serverUrl} disabled={busy} onChange={event => { serverEdited.current = true; setServerUrl(event.target.value); }} required aria-describedby="account-server-help" /></label>
      <p id="account-server-help" className="account-note">填写你部署的 HTTPS 根地址，可带端口，不含接口路径。地址由设备保存。</p>
      <label htmlFor="account-username">账号<input id="account-username" name="username" type="text" autoCapitalize="none" autoCorrect="off" spellCheck={false} autoComplete="off" maxLength={64} value={username} disabled={busy} onChange={event => { nameEdited.current = true; setUsername(event.target.value); }} required /></label>
      <label htmlFor="account-password">密码<input id="account-password" name="password" type="password" autoComplete="off" maxLength={256} value={password} disabled={busy} onChange={event => setPassword(event.target.value)} required enterKeyHint="go" /></label>
      <p className="account-note">使用服务器上已创建的账号。游客历史和其他账号的记录不会自动上传或混入此账号；未登录也可继续播放。</p>
      <div className="account-actions"><button type="submit" className="button primary" disabled={busy || !serverUrl.trim() || !username.trim() || !password}>{busy ? '登录中…' : account?.loginRequired ? '重新登录' : '登录并同步'}</button></div>
    </form>}
  </section>;
}
