// Diagnose why gem slots are not seen: what exactly is in the main hand?
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

  console.log('quickBarSlot=' + bot.quickBarSlot)
  bot.setQuickBarSlot(0)
  await sleep(500)
  console.log('after setQuickBarSlot(0): quickBarSlot=' + bot.quickBarSlot)

  const dump = (item) => {
    if (!item) return 'null'
    return JSON.stringify({
      name: item.name,
      displayName: item.displayName && item.displayName.toString(),
      count: item.count,
      slot: item.slot,
      // custom data / components carry sertraline_id
      components: item.components ? JSON.stringify(item.components).slice(0, 1500) : null
    })
  }

  console.log('heldItem=' + dump(bot.heldItem))
  console.log('inventory items:')
  for (const it of bot.inventory.items()) {
    console.log('  slot=' + it.slot + ' name=' + it.name + ' display=' + (it.displayName && it.displayName.toString()))
  }

  // Ask the plugin what it thinks the main-hand item resolves to.
  log.length = 0
  bot.chat('/sertralineapi evalJS var B=Java.type("org.bukkit.Bukkit"),P=B.getPlayer("SrTestBot"),I=P.getInventory().getItemInMainHand();I.getType().name()')
  await sleep(2000)
  for (const l of log) console.log('JS> ' + l)

  log.length = 0
  bot.chat('/sertralineapi evalJS var B=Java.type("org.bukkit.Bukkit"),P=B.getPlayer("SrTestBot");P.getInventory().getHeldItemSlot()')
  await sleep(2000)
  for (const l of log) console.log('JS2> ' + l)

  bot.quit()
}

main().catch(e => { console.log('FATAL ' + e.message); process.exit(1) })
