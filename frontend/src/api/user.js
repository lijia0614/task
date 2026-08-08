import request from './request'

export const listUsers = (params) => request.get('/users', { params })
export const listUserCandidates = () => request.get('/users/candidates')
export const createUser = (data) => request.post('/users', data)
export const updateUser = (id, data) => request.put(`/users/${id}`, data)
export const deleteUser = (id) => request.delete(`/users/${id}`)
export const resetPassword = (id, password) => request.put(`/users/${id}/password`, { password })
