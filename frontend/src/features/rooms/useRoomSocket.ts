import { useEffect, useEffectEvent, useState } from 'react'
import { Client } from '@stomp/stompjs'
import { getSocketAccessToken } from '../auth/authApi'

export function useRoomSocket(roomId: string, refresh: () => void) {
  const [connected, setConnected] = useState(false)
  const update = useEffectEvent(refresh)

  useEffect(() => {
    let active = true
    let previous: string | undefined
    const url = new URL('/ws/messages', window.location.href)
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
    const socket = new Client({
      brokerURL: url.href,
      reconnectDelay: 3000,
      connectionTimeout: 10000,
      onConnect: () => {
        if (!active) return
        socket.subscribe('/user/queue/rooms/' + roomId, frame => {
          if (!active) return
          try {
            const event = JSON.parse(frame.body) as { roomId: string }
            if (event.roomId === roomId) update()
          } catch {
            // REST history remains available.
          }
        })
        setConnected(true)
        update()
      },
      onWebSocketClose: () => { if (active) setConnected(false) },
      onStompError: () => { if (active) setConnected(false) },
    })
    socket.beforeConnect = async () => {
      try {
        previous = await getSocketAccessToken(previous)
        socket.connectHeaders = { Authorization: 'Bearer ' + previous }
      } catch {
        socket.connectHeaders = {}
      }
    }
    socket.activate()
    return () => {
      active = false
      void socket.deactivate()
    }
  }, [roomId])
  return connected
}
