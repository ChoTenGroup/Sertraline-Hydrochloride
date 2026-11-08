// Sertraline runtime test bot.
// Joins the local test server, waits to be opped by the console, then exercises the
// item pipeline: give items, inspect the received ItemStack NBT, reload the plugin
// while holding them, and verify the items survive a relog.
const mineflayer = require('E:/mineflayer')

const HOST = '127.0.0.1'
const PORT = 1082
const NAME = 'SrTestBot'

const sleep = ms => new Promise(r => setTimeout(r, ms))

function itemSummary (item) {
  if (!item) return null
  return {
    name: item.name,
    displayName: item.displayName && item.displayName.toString(),
    count: item.count,
    customName: item.customName || null,
    lore: (item.customLore || []).map(l => l.toString()),
    components: Object.keys(item.components || {}),
    nbt: item.nbt ? JSON.stringify(item.nbt).slice(0, 1200) : null
  }
}

async function main () {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: NAME, auth: 'offline' })

  bot.on('kicked', r => console.log('KICKED:', JSON.stringify(r)))
  bot.on('error', e => console.log('ERROR:', e.message))

  await new Promise((resolve, reject) => {
    bot.once('spawn', resolve)
    setTimeout(() => reject(new Error('spawn timeout')), 60000)
  })
  console.log('STEP spawn ok, username=' + bot.username)

  const waitOp = async () => {
    for (let i = 0; i < 30; i++) {
      if (bot.player && bot.player.opLevel !== undefined && bot.player.opLevel > 0) return bot.player.opLevel
      await sleep(1000)
    }
    return bot.player ? bot.player.opLevel : null
  }

  const opLevel = await waitOp()
  console.log('STEP opLevel=' + opLevel)

  // Give a few items and inspect what actually arrives in the inventory.
  const ids = ['doe_card', 'star_of_recall', 'sword_of_eternal_frozen', 'sertraline_pills_14']
  for (const id of ids) {
    bot.chat('/sertralineitem give ' + id)
    await sleep(1200)
  }

  const inv = bot.inventory.items().map(itemSummary)
  console.log('STEP inventory=' + JSON.stringify(inv, null, 1))

  // The book of multi language / comparable items exercise lore + data components.
  bot.chat('/sertralineitem give book_of_multi_language')
  await sleep(1200)
  const book = bot.inventory.items().find(i => i.name === 'written_book' || i.name === 'book')
  console.log('STEP book=' + JSON.stringify(itemSummary(book), null, 1))

  // Reload while items are held: the cached items must be rebuilt without errors.
  bot.chat('/sertraline reload')
  await sleep(9000)
  console.log('STEP reload done, inventoryCount=' + bot.inventory.items().length)

  // Drop nothing; just report and leave the inventory for a relog check.
  console.log('STEP DONE')
  await sleep(1000)
  bot.quit()
}

main().catch(e => {
  console.log('FATAL: ' + e.message)
  process.exit(1)
})
