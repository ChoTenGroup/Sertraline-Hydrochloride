// Runs in-game commands as the test bot and captures everything the server sends back
// as chat, so asynchronous replies (submitAsync + infoS) are visible.
// Usage: node bot-cmd.js "cmd1" "cmd2" ...
const mineflayer = require('E:/mineflayer')

const HOST = '127.0.0.1'
const PORT = 1082
const NAME = 'SrTestBot'
const WAIT = 1800

const sleep = ms => new Promise(r => setTimeout(r, ms))

async function main () {
  const commands = process.argv.slice(2)
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: NAME, auth: 'offline' })
  let bucket = []

  bot.on('message', (msg) => {
    bucket.push(msg.toString())
  })
  bot.on('kicked', r => console.log('KICKED:', JSON.stringify(r)))
  bot.on('error', e => console.log('ERROR:', e.message))

  await new Promise((resolve, reject) => {
    bot.once('spawn', resolve)
    setTimeout(() => reject(new Error('spawn timeout')), 60000)
  })
  await sleep(1500)
  bucket = []

  for (const cmd of commands) {
    bucket = []
    bot.chat(cmd.startsWith('/') ? cmd : '/' + cmd)
    await sleep(WAIT)
    console.log('=== ' + cmd)
    if (bucket.length === 0) console.log('(no output)')
    for (const line of bucket) console.log(line)
  }

  bot.quit()
}

main().catch(e => {
  console.log('FATAL: ' + e.message)
  process.exit(1)
})
