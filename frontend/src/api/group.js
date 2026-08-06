import request from './request'

export const listGroups = () => request.get('/groups')
export const groupMembers = (id) => request.get(`/groups/${id}/members`)
export const createGroup = (data) => request.post('/groups', data)
export const updateGroup = (id, data) => request.put(`/groups/${id}`, data)
export const deleteGroup = (id) => request.delete(`/groups/${id}`)
export const addMember = (id, userId) => request.post(`/groups/${id}/members`, { userId })
export const removeMember = (id, userId) => request.delete(`/groups/${id}/members/${userId}`)
