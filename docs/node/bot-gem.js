// Gem system end-to-end probe.
// Verifies: slots load -> socket applies stats -> colour gate -> rebuild keeps state ->
// unsocket reverts stats.
const mineflayer = require('E:/mineflayer')

const HOST = '127.0.0.1'
const PORT = 1082
const NAME = 'SrTestBot'
const sleep = ms => new Promise(r => setTimeout(r, ms))

const LORE =
  '/sertralineapi evalJS var B=Java.type("org.bukkit.Bukkit"),P=B.getPlayer("SrTestBot"),' +
  'I=P.getInventory().getItemInMainHand(),T=I.getItemMeta();T.getLore()'

async function main () {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: NAME, auth: 'offline' })
  const log = []
  bot.on('message', m => log.push(m.toString()))
  bot.on('error', e => console.log('ERROR ' + e.message))

  await new Promise((res, rej) => {
    bot.once('spawn', res)
    setTimeout(() => rej(new Error('spawn timeout')), 60000)
  })
  await sleep(2000)
  bot.setQuickBarSlot(0)
  await sleep(500)

  const run = async (cmd, waitMs = 1800) => {
    log.length = 0
    bot.chat(cmd)
    await sleep(waitMs)
    console.log('=== ' + cmd)
    if (log.length === 0) console.log('(no output)')
    for (const l of log) console.log(l)
  }
  const lore = async (label) => {
    console.log('--- lore: ' + label)
    await run(LORE, 2200)
  }

  await run('/clear SrTestBot', 1500)
  await run('/sertralineitem give gem_example_sword')
  await run('/sertralineitem give gem_example_ruby')
  await run('/sertralineitem give gem_example_sapphire')
  bot.setQuickBarSlot(0)
  await sleep(600)

  await run('/sgem list')
  await lore('fresh sword, expect Attack: 100 and empty sockets')

  await run('/sgem socket slot_red gem_example_ruby', 2500)
  await run('/sgem list')
  await lore('ruby socketed, expect Attack: 115')

  await run('/sgem socket slot_any gem_example_sapphire', 2500)
  await run('/sgem list')
  await lore('sapphire socketed, expect Attack: 140')

  // The red socket must refuse a blue gem.
  await run('/sgem unsocket slot_any', 2500)
  await run('/sgem socket slot_red gem_example_sapphire', 2000)
  await lore('after refusing sapphire in red socket, expect Attack: 115')

  await run('/sgem socket slot_any gem_example_sapphire', 2500)
  await run('/sgem unsocket slot_red', 2500)
  await run('/sgem list')
  await lore('ruby removed, expect Attack: 125 and only slot_any filled')

  await run('/sgem unsocket slot_any', 2500)
  await run('/sgem list')
  await lore('all removed, expect Attack: 100 and empty sockets')

  bot.quit()
}

main().catch(e => {
  console.log('FATAL ' + e.message)
  process.exit(1)
})
