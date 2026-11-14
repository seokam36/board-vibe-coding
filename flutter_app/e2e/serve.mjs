// 미리 빌드해 둔 Flutter 웹(../build/web)을 포트 5176에서 보여 주는 아주 작은 정적 서버.
// 왜 직접 만드나: 의존성을 늘리지 않으려고(node 기본 기능만 쓴다).
// /api 요청은 테스트가 page.route로 가로채므로 서버는 신경 쓰지 않고 404만 준다.
import { createServer } from 'node:http'
import { existsSync } from 'node:fs'
import { readFile, stat } from 'node:fs/promises'
import { extname, join, normalize, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(fileURLToPath(new URL('../build/web', import.meta.url)))
const port = Number(process.env.PORT || 5176)

// 파일 종류별 Content-Type (Flutter 웹이 쓰는 것만)
const types = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.wasm': 'application/wasm',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.otf': 'font/otf',
  '.ttf': 'font/ttf',
  '.woff2': 'font/woff2',
  '.map': 'application/json',
  '.symbols': 'text/plain',
}

// 시작할 때 빌드 결과가 있는지 확인한다: 없으면 빈 화면 대신 바로 이유를 알려 주고 멈춘다
if (!existsSync(join(root, 'index.html'))) {
  console.error('build/web/index.html이 없다. 먼저 flutter_app에서 `flutter build web --web-content-hash`를 실행해라.')
  process.exit(1)
}

async function isFile(path) {
  try {
    return (await stat(path)).isFile()
  } catch {
    return false
  }
}

createServer(async (req, res) => {
  const { pathname } = new URL(req.url ?? '/', 'http://localhost')
  // /api는 가짜 응답 담당(테스트)이라 여기서는 404
  if (pathname.startsWith('/api/')) {
    res.writeHead(404, { 'Content-Type': 'application/json' })
    res.end('{"message":"e2e 서버에는 /api가 없다 (page.route로 가로채야 한다)"}')
    return
  }
  // ../ 로 build/web 밖의 파일을 읽지 못하게 막는다
  // 주소에 깨진 % 인코딩(예: /%E0%A4%A)이 있으면 decodeURIComponent가 예외를 던지므로 400으로 막는다
  let decoded
  try {
    decoded = decodeURIComponent(pathname)
  } catch {
    res.writeHead(400)
    res.end('bad request')
    return
  }
  const file = normalize(join(root, decoded))
  let target = file
  if (!(file === root || file.startsWith(root + sep)) || !(await isFile(file))) {
    // 확장자 없는 경로(/posts/1 같은 화면 주소)는 index.html로: 화면 이동은 앱이 알아서 한다(SPA)
    if (extname(pathname) !== '') {
      res.writeHead(404)
      res.end('not found')
      return
    }
    target = join(root, 'index.html')
  }
  try {
    const body = await readFile(target)
    res.writeHead(200, { 'Content-Type': types[extname(target)] ?? 'application/octet-stream' })
    res.end(body)
  } catch {
    res.writeHead(500)
    res.end('error')
  }
}).listen(port, () => console.log(`Flutter 웹 e2e 서버: http://localhost:${port}`))
