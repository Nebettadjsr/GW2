import { describe, expect, it } from 'vitest'
import { isAllowedUrl, isRequiredApiFailure } from '../capacityGuard.mjs'

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

describe('ordinary capacity user API outcomes', () => {
  it('accepts expected owner discovery denial and successful ordinary status data', () => {
    expect(isRequiredApiFailure({ path: '/api/system/status/details', status: 403 })).toBe(false)
    expect(isRequiredApiFailure({ path: '/api/system/status', status: 200 })).toBe(false)
  })
  it('keeps missing sessions, failed capability checks and denied required reads as failures', () => {
    expect(isRequiredApiFailure({ path: '/api/system/status/details', status: 401 })).toBe(true)
    expect(isRequiredApiFailure({ path: '/api/system/status/details', status: 500 })).toBe(true)
    expect(isRequiredApiFailure({ path: '/api/system/status', status: 403 })).toBe(true)
    expect(isRequiredApiFailure({ path: '/api/account/bank', status: 403 })).toBe(true)
  })
})
