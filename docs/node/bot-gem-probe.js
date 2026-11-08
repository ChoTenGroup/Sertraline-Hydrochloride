// Probe v10: the item-update path (updater + rebuild + preserveKeptData) must not disturb gem stats.
const mineflayer = require('E:/mineflayer')
const HOST = '127.0.0.1'
const PORT = 1082
const sleep = ms => new Promise(r => setTimeout(r, ms))

async function main () {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: 'SrTestBot', auth: 'offline' })
  const log = []
  bot.on('message', m => log.push(m.toString()))
  bot.on('error', e => console.log('ERROR ' + e.message))
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 60000) })
  await sleep(2000)
  bot.setQuickBarSlot(0)
  await sleep(500)

  const run = async (cmd, waitMs = 2800) => {
    log.length = 0
    bot.chat(cmd)
    await sleep(waitMs)
    for (const l of log) if (!l.includes('issued server command') && !l.includes('JS: ')) console.log('  ' + l)
  }

  const state = (label) => {
    const it = bot.heldItem
    let atk = '?'
    let slots = ''
    for (const c of (it && it.components) || []) {
      const s = JSON.stringify(c)
      if (s && s.includes('sertraline_data')) {
        const m = s.match(/"atk":\{"type":"long","value":\[(-?\d+),(-?\d+)\]/)
        if (m) atk = String((Number(m[1]) * 4294967296) + (Number(m[2]) >>> 0))
      }
      if (c && c.type === 'lore') {
        const raw = JSON.stringify(c.data || [])
        const filled = (raw.match(/Socket: /g) || []).length
        slots = 'filled=' + filled
      }
    }
    console.log(label + ' -> atk=' + atk + '  ' + slots)
  }

  await run('/clear SrTestBot', 1500)
  await run('/sertralineitem give gem_example_sword')
  await run('/sertralineitem give gem_example_ruby')
  await run('/sertralineitem give gem_example_sapphire')
  bot.setQuickBarSlot(0)
  await sleep(600)

  await run('/sgem socket slot_red gem_example_ruby')
  state('socket ruby               (expect 115, filled=1)')

  await run('/sertralinedebug update true', 4000)
  state('after forced item update  (expect 115, filled=1)')

  await run('/sgem socket slot_any gem_example_sapphire')
  state('socket sapphire           (expect 140, filled=2)')

  await run('/sertralinedebug update true', 4000)
  state('after forced item update  (expect 140, filled=2)')

  await run('/sgem unsocket slot_any')
  await run('/sgem unsocket slot_red')
  state('all removed               (expect 100, filled=0)')

  bot.quit()
}

main().catch(e => { console.log('FATAL ' + e.message); process.exit(1) })
