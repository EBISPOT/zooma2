
import express from 'express'
import fetch from 'node-fetch'
import urlJoin from 'url-join'
import nocache from 'nocache'


let server = express()

server.use(nocache())

if(process.env.ZOOMA_DEV_BACKEND_PROXY_URL === undefined) {
    throw new Error('please set ZOOMA_DEV_BACKEND_PROXY_URL before running dev server')
}

// Parse JSON bodies for API proxy (needed to forward POST bodies)
server.use(/^\/v[23].*/, express.json({ limit: '10mb' }))

server.use(/^\/v[23].*/, async (req, res) => {
  let backendUrl = urlJoin(process.env.ZOOMA_DEV_BACKEND_PROXY_URL, req.originalUrl)
  console.log('forwarding api request to: ' + backendUrl)
  try {
    let body = undefined
    let headers = {}
    if (req.method !== 'GET' && req.method !== 'HEAD' && req.body) {
      body = JSON.stringify(req.body)
      headers['content-type'] = 'application/json'
    }
    let apiResponse = await fetch(backendUrl, {
      redirect: 'follow',
      method: req.method,
      body,
      headers,
      // 10 min timeout for streaming endpoints where individual properties can take a while
      timeout: 600000,
    })
    res.header('content-type', apiResponse.headers.get('content-type'))
    res.status(apiResponse.status)
    apiResponse.body.pipe(res)
  } catch(e) {
    console.log(e)
  }
})

server.use(express.static('dist'))

server.get(/^(?!\/api).*$/, (req, res) => {
  res.sendFile(process.cwd() + '/dist/index.html')
})



    
server.listen(3000)    




