import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { notificationsApi } from '../api/endpoints';
import { connectNotifications } from '../api/stomp';
import { useAuth } from './AuthContext';
import { sameId } from '../lib/ids';
import { rolesOf } from '../constants/accessPolicy';

const NotificationContext = createContext(null);
const MAX_ITEMS = 100;

/**
 * Thông báo thời gian thực: nạp danh sách gần nhất (GET /notifications) rồi nhận thông báo mới qua WebSocket STOMP.
 * Backend lưu trong bộ nhớ nên danh sách có thể trống sau khi dịch vụ khởi động lại. Trạng thái "đã đọc" do backend giữ riêng từng người
 * (trường `read`, POST /notifications/{id}/read).
 */
export function NotificationProvider({ children }) {
  const { user } = useAuth();
  const [items, setItems] = useState([]);
  const [connection, setConnection] = useState('idle');
  const [toast, setToast] = useState(null);
  const listeners = useRef(new Set());
  const itemsRef = useRef([]);
  itemsRef.current = items;
  const userId = user?.id;
  const rolesKey = rolesOf(user).join(',');   // chuỗi ổn định để effect không chạy lại khi mảng đổi tham chiếu

  const push = useCallback((incoming, live) => {
    setItems((current) => {
      if (current.some((item) => sameId(item.id, incoming.id))) return current;
      return [incoming, ...current].sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt))).slice(0, MAX_ITEMS);
    });
    if (live) {
      setToast(incoming);
      listeners.current.forEach((listener) => { try { listener(incoming); } catch { /* lỗi của người nghe không làm hỏng luồng thông báo */ } });
    }
  }, []);

  useEffect(() => {
    setItems([]); setToast(null);
    if (!userId) { setConnection('idle'); return undefined; }
    let alive = true;
    notificationsApi.list(50).then((list) => { if (alive) (list || []).forEach((item) => push(item, false)); }).catch(() => {});
    const close = connectNotifications({ roles: rolesKey ? rolesKey.split(',') : [], onMessage: (message) => push(message, true), onState: setConnection });
    return () => { alive = false; close(); };
  }, [userId, rolesKey, push]);

  useEffect(() => {
    if (!toast) return undefined;
    const timer = setTimeout(() => setToast(null), toast.severity === 'CRITICAL' ? 15000 : 7000);
    return () => clearTimeout(timer);
  }, [toast]);

  const markAllRead = useCallback(() => {
    const unreadItems = itemsRef.current.filter((item) => !item.read);
    if (!unreadItems.length) return;
    setItems((current) => current.map((item) => (item.read ? item : { ...item, read: true })));
    // Báo backend (tối đa 30 thông báo mỗi lần); lỗi mạng chỉ làm mất trạng thái đã đọc phía máy chủ
    unreadItems.slice(0, 30).forEach((item) => { notificationsApi.markRead(item.id).catch(() => {}); });
  }, []);

  const subscribe = useCallback((listener) => { listeners.current.add(listener); return () => listeners.current.delete(listener); }, []);
  const unread = useMemo(() => items.filter((item) => !item.read).length, [items]);
  const value = useMemo(() => ({ items, unread, connection, markAllRead, subscribe, toast, dismissToast: () => setToast(null) }), [items, unread, connection, markAllRead, subscribe, toast]);
  return <NotificationContext.Provider value={value}>{children}</NotificationContext.Provider>;
}

export function useNotifications() {
  return useContext(NotificationContext) || { items: [], unread: 0, connection: 'idle', markAllRead() {}, subscribe: () => () => {}, toast: null, dismissToast() {} };
}

/** Gọi `handler` mỗi khi có thông báo mới cùng loại (hoặc mọi loại nếu không truyền `types`) — dùng để làm mới màn hình điều phối. */
export function useNotificationEvents(handler, types) {
  const { subscribe } = useNotifications();
  const latest = useRef(handler);
  latest.current = handler;
  const key = types ? types.join('|') : '';
  useEffect(() => subscribe((message) => { if (!key || key.split('|').includes(message.type)) latest.current(message); }), [subscribe, key]);
}
