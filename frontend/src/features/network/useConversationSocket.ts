import { useEffect, useEffectEvent, useRef, useState } from 'react'
import { Client } from '@stomp/stompjs'
import { getSocketAccessToken } from '../auth/authApi'

export interface ConversationEvent {
  type: 'MESSAGE' | 'READ' | 'TYPING' | 'PRESENCE' | 'DELETED'
  conversationId: string
  userId: string
  messageId?: string
  online?: boolean
}

export function useConversationSocket(id: string, onEvent: (event: ConversationEvent) => void, onConnected: () => void) {
  const client = useRef<Client | null>(null)
  const lastTyping = useRef(0)
  const [connected, setConnected] = useState(false)
  const receive = useEffectEvent(onEvent)
  const recover = useEffectEvent(onConnected)

  useEffect(() => {
    let active = true
    let previousToken: string | undefined
    const url = new URL('/ws/messages', window.location.href)
    url.protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const socket = new Client({
      brokerURL: url.href,
      reconnectDelay: 3000,
      connectionTimeout: 10000,
      onConnect: () => {
        if (!active) return
        socket.subscribe('/user/queue/conversations/' + id, frame => {
          if (!active) return
          try {
            const event = JSON.parse(frame.body) as ConversationEvent
            if (event.conversationId === id) receive(event)
          } catch {
            // Invalid broker data should not interrupt REST history recovery.
          }
        })
        setConnected(true)
        socket.publish({ destination: '/app/conversations/' + id + '/presence' })
        recover()
      },
      onWebSocketClose: () => { if (active) setConnected(false) },
      onStompError: () => { if (active) setConnected(false) },
    })
    socket.beforeConnect = async () => {
      try {
        previousToken = await getSocketAccessToken(previousToken)
        socket.connectHeaders = { Authorization: 'Bearer ' + previousToken }
      } catch {
        // Connecting without credentials will be rejected and retried.
        socket.connectHeaders = {}
      }
    }
    client.current = socket
    socket.activate()
    const presenceTimer = window.setInterval(() => {
      if (socket.connected) socket.publish({ destination: '/app/conversations/' + id + '/presence' })
    }, 15000)
    return () => {
      active = false
      client.current = null
      window.clearInterval(presenceTimer)
      void socket.deactivate()
    }
  }, [id])

  function sendTyping() {
    if (!client.current?.connected || Date.now() - lastTyping.current < 2500) return
    lastTyping.current = Date.now()
    client.current.publish({ destination: '/app/conversations/' + id + '/typing' })
  }

  return { connected, sendTyping }
}
