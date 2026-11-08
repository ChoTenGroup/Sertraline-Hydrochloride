// Minimal Minecraft RCON client (no dependencies) for driving the local test server.
// Usage: node rcon.js "<command>" ["<command>" ...]
const net = require('net')

const HOST = '127.0.0.1'
const PORT = 25575
const PASSWORD = 'sertralinetest'

function packet(id, type, body) {
  const payload = Buffer.from(body, 'utf8')
  const buf = Buffer.alloc(payload.length + 14)
  buf.writeInt32LE(payload.length + 10, 0)
  buf.writeInt32LE(id, 4)
  buf.writeInt32LE(type, 8)
  payload.copy(buf, 12)
  buf.writeInt16LE(0, payload.length + 12)
  return buf
}

function readPackets(socket, onPacket) {
  let buffer = Buffer.alloc(0)
  socket.on('data', chunk => {
    buffer = Buffer.concat([buffer, chunk])
    while (buffer.length >= 4) {
      const len = buffer.readInt32LE(0)
      if (buffer.length < len + 4) break
      const id = buffer.readInt32LE(4)
      const type = buffer.readInt32LE(8)
      const body = buffer.toString('utf8', 12, len + 4 - 2)
      buffer = buffer.slice(len + 4)
      onPacket(id, type, body)
    }
  })
}

function run(commands) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: HOST, port: PORT })
    const results = []
    let authed = false
    let index = 0
    const timer = setTimeout(() => {
      socket.destroy()
      reject(new Error('RCON timeout'))
    }, 30000)

    const next = () => {
      if (index >= commands.length) {
        clearTimeout(timer)
        socket.end()
        resolve(results)
        return
      }
      const cmd = commands[index++]
      socket.write(packet(100 + index, 2, cmd))
    }

    readPackets(socket, (id, type, body) => {
      if (!authed) {
        if (id === -1) {
          clearTimeout(timer)
          socket.destroy()
          reject(new Error('RCON auth failed'))
          return
        }
        authed = true
        next()
        return
      }
      results.push(body)
      next()
    })

    socket.on('connect', () => socket.write(packet(1, 3, PASSWORD)))
    socket.on('error', err => {
      clearTimeout(timer)
      reject(err)
    })
  })
}

;(async () => {
  const commands = process.argv.slice(2)
  try {
    const results = await run(commands)
    for (let i = 0; i < commands.length; i++) {
      console.log('--- ' + commands[i])
      console.log((results[i] || '').trim() || '(empty)')
    }
  } catch (e) {
    console.error('ERROR: ' + e.message)
    process.exit(1)
  }
})()
