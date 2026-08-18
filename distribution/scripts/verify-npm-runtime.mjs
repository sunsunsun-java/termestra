#!/usr/bin/env node
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const CHECK_OPTIONS={encoding:'utf8',timeout:15_000,maxBuffer:1024*1024,killSignal:'SIGKILL'}

const target=process.argv[2]
if(!target){console.error('Usage: node verify-npm-runtime.mjs <distribution-target>');process.exit(2)}
const platform=`${process.platform}-${process.arch}`
const runtime=join(target,'npm',`runtime-${platform}`)
const cli=join(target,'npm-cli')
const repositoryRoot=resolve(dirname(fileURLToPath(import.meta.url)),'../..')
const readmeNames=['README.md','README.zh-CN.md']
const localReadmeImages=(markdown)=>[...markdown.matchAll(/<img\s+[^>]*src=["']([^"']+)["']/g)]
  .map((match)=>match[1])
  .filter((source)=>!source.startsWith('http://')&&!source.startsWith('https://'))
  .map((source)=>source.replace(/^\.\//,''))
const manifest=JSON.parse(readFileSync(join(runtime,'package.json'),'utf8'))
assert.equal(manifest.name,`@termestra/runtime-${platform}`)
assert.deepEqual(manifest.os,[process.platform])
assert.deepEqual(manifest.cpu,[process.arch])
assert.ok(existsSync(join(runtime,'app','termestra.jar')),'application jar is missing')
assert.ok(existsSync(join(runtime,'LICENSE.BSL')),'runtime source license is missing')
assert.ok(existsSync(join(runtime,'LICENSE')),'runtime historical license notice is missing')
assert.ok(existsSync(join(runtime,'NOTICE')),'runtime NOTICE is missing')
assert.ok(existsSync(join(runtime,'TRADEMARK.md')),'runtime trademark notice is missing')
assert.ok(existsSync(join(runtime,'THIRD_PARTY_NOTICES.md')),'runtime third-party notices are missing')
const java=join(runtime,'runtime','bin',process.platform==='win32'?'java.exe':'java')
assert.ok(existsSync(java),'embedded Java launcher is missing')
const javaResult=spawnSync(java,['-version'],CHECK_OPTIONS)
assert.ifError(javaResult.error)
assert.equal(javaResult.status,0,javaResult.stderr||javaResult.stdout)
const cliResult=spawnSync(process.execPath,[join(cli,'bin','termestra.mjs'),'--version'],CHECK_OPTIONS)
assert.ifError(cliResult.error)
assert.equal(cliResult.status,0,cliResult.stderr)
assert.equal(cliResult.stdout.trim(),manifest.version)
const updateHelp=spawnSync(process.execPath,[join(cli,'bin','termestra.mjs'),'update','--help'],CHECK_OPTIONS)
assert.ifError(updateHelp.error)
assert.equal(updateHelp.status,0,updateHelp.stderr)
assert.match(updateHelp.stdout,/Usage: termestra update/)
for(const readmeName of readmeNames){
  const readmePath=join(cli,readmeName)
  assert.ok(existsSync(readmePath),`npm CLI ${readmeName} is missing`)
  const markdown=readFileSync(readmePath,'utf8')
  for(const imagePath of localReadmeImages(markdown)){
    const stagedImagePath=join(cli,imagePath)
    const sourceImagePath=join(repositoryRoot,imagePath)
    assert.ok(existsSync(stagedImagePath),`${readmeName} image is missing: ${imagePath}`)
    assert.ok(existsSync(sourceImagePath),`${readmeName} source image is missing: ${imagePath}`)
    assert.deepEqual(
      readFileSync(stagedImagePath),
      readFileSync(sourceImagePath),
      `${readmeName} image is stale: ${imagePath}`,
    )
  }
}
assert.ok(existsSync(join(cli,'LICENSE.BSL')),'npm CLI source license is missing')
assert.ok(existsSync(join(cli,'LICENSE')),'npm CLI historical license notice is missing')
assert.ok(existsSync(join(cli,'NOTICE')),'npm CLI NOTICE is missing')
assert.ok(existsSync(join(cli,'TRADEMARK.md')),'npm CLI trademark notice is missing')
assert.ok(existsSync(join(cli,'THIRD_PARTY_NOTICES.md')),'npm CLI third-party notices are missing')
assert.ok(existsSync(join(cli,'frontend','web','public','logo.png')),'npm CLI README logo is missing')
assert.ok(existsSync(join(cli,'docs','README.md')),'npm CLI documentation map is missing')
assert.ok(existsSync(join(cli,'docs','architecture','overview.md')),'npm CLI architecture overview is missing')
assert.ok(existsSync(join(cli,'docs','product','roadmap.md')),'npm CLI roadmap is missing')
assert.ok(existsSync(join(cli,'docs','governance','licensing-review.md')),'npm CLI licensing review is missing')
assert.ok(existsSync(join(cli,'backend','src','main','resources','vendor','marketplace','en','LICENSE')),'npm CLI marketplace license is missing')
assert.ok(existsSync(join(cli,'frontend','web','public','sounds','LICENSE-KENNEY.txt')),'npm CLI sound attribution is missing')

console.log(`Verified ${manifest.name}@${manifest.version}`)
