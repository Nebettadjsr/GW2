import { describe, expect, it } from 'vitest'
import { isAllowedUrl } from '../capacityGuard.mjs'

describe('capacity probe request guard', () => {
  it('allows loopback http and inline resources', () => {
    for (const u of ['http://127.0.0.1:5173/api/items/1/icon/ab.png', 'http://localhost:80/', 'http://[::1]:8080/x', 'data:image/png;base64,AA==', 'blob:http://127.0.0.1:1/x', 'about:blank'])
      expect(isAllowedUrl(u), u).toBe(true)
  })
  it('blocks ArenaNet and any other non-loopback host, tricky hosts and other schemes', () => {
    for (const u of ['https://render.guildwars2.com/file/AB12/123.png', 'https://api.guildwars2.com/v2/items', 'http://127.0.0.1.evil.example/', 'http://localhost.evil.example/', 'https://127.0.0.1/', 'http://10.0.0.1/', 'ftp://127.0.0.1/', 'not a url', ''])
      expect(isAllowedUrl(u), u).toBe(false)
  })
})
