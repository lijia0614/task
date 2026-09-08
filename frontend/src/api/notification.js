import request from './request'

export const listNotifications = (params) => request.get('/notifications', { params })
export const unreadCount = () => request.get('/notifications/unread-count')
export const markRead = (id) => request.put(`/notifications/${id}/read`)
export const markAllRead = () => request.put('/notifications/read-all')
