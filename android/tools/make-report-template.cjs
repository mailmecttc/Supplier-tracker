// Builds app/src/main/assets/report-template.pptx: the neutral template used for the weekly report
// until the user imports their own company template. Run: node tools/make-report-template.cjs
const path = require('path');
const pptxgen = require('pptxgenjs');
const { applyTheme } = require(process.env.PPTX_SKILL + '/scripts/apply_theme.js');

const THEME = {
  name: 'Supplier Tracker',
  headFontFace: 'Calibri',
  bodyFontFace: 'Calibri',
  colors: {
    dk1: '1F2937', lt1: 'FFFFFF', dk2: '1F4E79', lt2: 'E7ECF2',
    accent1: '1F4E79', accent2: 'A23B2A', accent3: '5B8DB8', accent4: '2E7D32', accent5: 'C9A227', accent6: '6B7280',
    hlink: '1F4E79', folHlink: '5B8DB8',
  },
};

(async () => {
  const pres = new pptxgen();
  pres.layout = 'LAYOUT_WIDE'; // 13.33 x 7.5 in
  pres.theme = { headFontFace: THEME.headFontFace, bodyFontFace: THEME.bodyFontFace };
  pres.title = 'Weekly report template';

  pres.defineSlideMaster({
    title: 'Title Slide',
    background: { color: '1F4E79' },
    objects: [
      { placeholder: { options: { name: 'title', type: 'title', x: 0.8, y: 2.5, w: 11.7, h: 1.1, fontSize: 40, bold: true, color: 'FFFFFF', valign: 'bottom' }, text: '' } },
      { placeholder: { options: { name: 'body', type: 'body', x: 0.8, y: 3.75, w: 11.7, h: 0.7, fontSize: 20, color: 'CADCFC' }, text: '' } },
      { text: { text: 'Supplier Tracker', options: { x: 0.8, y: 6.7, w: 6, h: 0.4, fontSize: 12, color: 'CADCFC' } } },
    ],
  });
  pres.defineSlideMaster({
    title: 'Slide with Title',
    background: { color: 'FFFFFF' },
    objects: [
      { placeholder: { options: { name: 'title', type: 'title', x: 0.57, y: 0.4, w: 12.2, h: 0.95, fontSize: 30, color: '1F2937', valign: 'middle' }, text: '' } },
    ],
    slideNumber: { x: 12.3, y: 7.0, w: 0.5, h: 0.3, fontSize: 10, color: '6B7280' },
  });
  pres.defineSlideMaster({
    title: 'Final Slide',
    background: { color: '1F4E79' },
    objects: [{ text: { text: 'Thank you', options: { x: 0.8, y: 3.0, w: 11.7, h: 1.2, fontSize: 40, bold: true, color: 'FFFFFF', align: 'center' } } }],
  });

  const s = pres.addSlide({ masterName: 'Title Slide' });
  s.addText('Weekly report', { placeholder: 'title' });

  const out = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'report-template.pptx');
  await pres.writeFile({ fileName: out });
  await applyTheme(out, THEME);
  console.log('wrote', out);
})();
