'use strict';
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const base=process.env.DEMO_URL||'http://127.0.0.1:4200';
(async()=>{
 const browser=await chromium.launch({headless:true});
 try{
  const p=await browser.newPage({viewport:{width:1440,height:1000}});const errors=[];p.on('pageerror',e=>errors.push(e.message));
  for(let attempt=0;;attempt++){try{await p.goto(base);await p.locator('#search-term').waitFor({timeout:4000});break}catch(e){if(attempt>=29)throw e;await new Promise(r=>setTimeout(r,1000));}}
  await p.locator('#search-term').fill('Luna');
  await p.locator('a.result').first().waitFor();
  assert.equal(await p.locator('a.result').count(),1);
  assert.match(await p.locator('a.result').innerText(),/MRN-88213/);
  fs.mkdirSync('artifacts',{recursive:true});await p.screenshot({path:'artifacts/health-search.png',fullPage:true});
  await p.locator('a.result').click();await p.getByRole('heading',{name:'Ixequi Luna'}).waitFor();
  await p.getByText('VN-556677',{exact:true}).waitFor();await p.screenshot({path:'artifacts/health-record.png',fullPage:true});
  await p.getByRole('link',{name:'Pipeline',exact:true}).click();await p.locator('.service').first().waitFor();
  assert.equal(await p.locator('.service[data-health=unknown]').count(),3);
  assert.equal(await p.locator('.service[data-health=healthy]').count(),2);
  assert.match(await p.locator('.overall').innerText(),/Unknown/i);
  await p.screenshot({path:'artifacts/health-pipeline.png',fullPage:true});
  for(const width of [320,390,768,1440]){await p.setViewportSize({width,height:900});assert(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));}
  const bad=await p.request.post(base+'/interop.v1.PatientService/GetPatient',{data:{medicalRecordNumber:'missing'}});assert.equal(bad.status(),404);
  await p.goto(base+'/patients/missing');await p.getByRole('heading',{name:'Patient not found'}).waitFor();
  assert.deepEqual(errors,[]);console.log('Connected HTTP -> gRPC search, record, encounters, status, missing record and responsive checks passed.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1});
