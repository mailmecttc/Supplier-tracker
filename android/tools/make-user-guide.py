"""Builds the in-app user guide: app/src/main/assets/user-guide.pdf

Screens and slides come from made-up sample data (tools/guide-sample-data.json), never company data.

  1. Screenshots:  ./gradlew :app:testDebugUnitTest --tests '*AppUiTest.screenshots' \
                     -Pscreenshot.data=$PWD/tools/guide-sample-data.json -Pscreenshot.out=/tmp/shots
  2. Slides:       ./gradlew :app:testDebugUnitTest --tests '*ReportTest.localDecks' \
                     -Preport.template=$PWD/app/src/main/assets/report-template.pptx \
                     -Preport.data=$PWD/tools/guide-sample-data.json -Preport.out=/tmp/deck
                   soffice --headless --convert-to pdf --outdir /tmp/deck /tmp/deck/weekly-report.pptx
                   pdftoppm -r 115 -png /tmp/deck/weekly-report.pdf /tmp/slides/hi
  3. PDF:          python3 tools/make-user-guide.py /tmp/shots /tmp/slides

Needs reportlab, Pillow and the DejaVu Sans fonts. The page is phone-sized (5 x 7.5 in) because the app
shows each page at screen width.
"""
import io
import os
import re
import sys

from PIL import Image as PILImage, ImageDraw, ImageFont
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import inch
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (BaseDocTemplate, CondPageBreak, Frame, Image, KeepTogether, NextPageTemplate,
                                PageBreak, PageTemplate, Paragraph, Spacer, Table, TableStyle)
from reportlab.platypus.tableofcontents import TableOfContents

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SHOTS = sys.argv[1] if len(sys.argv) > 1 else '/tmp/shots'
SLIDES = sys.argv[2] if len(sys.argv) > 2 else '/tmp/slides'
OUT = sys.argv[3] if len(sys.argv) > 3 else os.path.join(ROOT, 'app/src/main/assets/user-guide.pdf')
VERSION = re.search(r'versionName = "([^"]+)"', open(os.path.join(ROOT, 'app/build.gradle.kts')).read()).group(1)

FONT_DIR = '/usr/share/fonts/truetype/dejavu'
pdfmetrics.registerFont(TTFont('Sans', f'{FONT_DIR}/DejaVuSans.ttf'))
pdfmetrics.registerFont(TTFont('Sans-Bold', f'{FONT_DIR}/DejaVuSans-Bold.ttf'))
pdfmetrics.registerFontFamily('Sans', normal='Sans', bold='Sans-Bold', italic='Sans', boldItalic='Sans-Bold')

BLUE = colors.HexColor('#1F4E79')
RED = colors.HexColor('#B3261E')
GREY = colors.HexColor('#5F6368')
LINE = colors.HexColor('#D0D7DE')
TINT = colors.HexColor('#EEF3F8')
WARN = colors.HexColor('#FCEFEA')

PAGE = (5 * inch, 7.5 * inch)
MARGIN = 22
WIDTH = PAGE[0] - 2 * MARGIN
SHOT_W = 126  # one phone screenshot next to text
LEFT_W = WIDTH - SHOT_W - 12  # the text column beside it

body = ParagraphStyle('body', fontName='Sans', fontSize=9.6, leading=13.2, spaceAfter=5)
small = ParagraphStyle('small', parent=body, fontSize=8.2, leading=10.8, textColor=GREY)
cell = ParagraphStyle('cell', parent=body, fontSize=8.6, leading=11.4, spaceAfter=0)
cellb = ParagraphStyle('cellb', parent=cell, fontName='Sans-Bold')
caption = ParagraphStyle('caption', parent=small, fontSize=7.8, leading=10, alignment=TA_CENTER, spaceBefore=3)
h1 = ParagraphStyle('h1', fontName='Sans-Bold', fontSize=15, leading=19, textColor=BLUE, spaceBefore=6, spaceAfter=8)
h2 = ParagraphStyle('h2', fontName='Sans-Bold', fontSize=10.8, leading=14, textColor=BLUE, spaceBefore=8, spaceAfter=4)
step = ParagraphStyle('step', parent=body, leftIndent=15, bulletIndent=0, spaceAfter=4)
bullet = ParagraphStyle('bullet', parent=body, leftIndent=11, bulletIndent=1, spaceAfter=3)
toc1 = ParagraphStyle('toc1', parent=body, fontSize=9.8, leading=15, spaceAfter=0)


class Guide(BaseDocTemplate):
    def __init__(self, path):
        super().__init__(path, pagesize=PAGE, leftMargin=MARGIN, rightMargin=MARGIN, topMargin=26, bottomMargin=30,
                         title='Supplier Tracker – User guide', author='Supplier Tracker',
                         subject='How to fill in the details, read the numbers and fix problems')
        frame = Frame(MARGIN, 30, WIDTH, PAGE[1] - 56, id='f', leftPadding=0, rightPadding=0, topPadding=0, bottomPadding=0)
        self.addPageTemplates([PageTemplate('cover', [frame]), PageTemplate('page', [frame], onPage=self.footer)])

    def footer(self, c, doc):
        c.saveState()
        c.setStrokeColor(LINE)
        c.line(MARGIN, 22, PAGE[0] - MARGIN, 22)
        c.setFont('Sans', 7.2)
        c.setFillColor(GREY)
        c.drawString(MARGIN, 12, f'Supplier Tracker {VERSION} · User guide')
        c.drawRightString(PAGE[0] - MARGIN, 12, str(doc.page))
        c.restoreState()

    def afterFlowable(self, f):
        # chapter headings feed the contents page and the PDF bookmarks
        if isinstance(f, Paragraph) and f.style.name == 'h1':
            text = f.getPlainText()
            key = 'sec-' + text.split('.')[0]
            self.canv.bookmarkPage(key)
            self.canv.addOutlineEntry(text, key, level=0)
            self.notify('TOCEntry', (0, text, self.page, key))


def P(text, style=body):
    return Paragraph(text, style)


def H1(text):
    return [CondPageBreak(220), P(text, h1)]


def H2(text, *follow):
    """A sub-heading, kept on the same page as the block that follows it."""
    if follow:
        # a KeepTogether nested in another reports a huge height and forces a page break, so unwrap it
        flat = [f for x in follow for f in (x._content if isinstance(x, KeepTogether) else [x])]
        return [CondPageBreak(60), KeepTogether([P(text, h2), *flat])]
    return [CondPageBreak(110), P(text, h2)]


def steps(items, start=1):
    return [Paragraph(t, step, bulletText=f'{i}.') for i, t in enumerate(items, start)]


def bullets(items):
    return [Paragraph(t, bullet, bulletText='•') for t in items]


def box(text, kind='tip', w=WIDTH):
    label, bg, fg = {'tip': ('Tip', TINT, BLUE), 'important': ('Important', WARN, RED)}[kind]
    t = Table([[Paragraph(f'<font color="#{fg.hexval()[2:]}"><b>{label}.</b></font> {text}', cell)]],
              colWidths=[w])
    t.setStyle(TableStyle([('BACKGROUND', (0, 0), (-1, -1), bg), ('LINEBEFORE', (0, 0), (0, -1), 2.5, fg),
                           ('LEFTPADDING', (0, 0), (-1, -1), 8), ('RIGHTPADDING', (0, 0), (-1, -1), 8),
                           ('TOPPADDING', (0, 0), (-1, -1), 6), ('BOTTOMPADDING', (0, 0), (-1, -1), 6)]))
    return [Spacer(1, 3), t, Spacer(1, 6)]


def table(rows, widths):
    """First row is the header (white on blue); the first column is bold. Short tables are kept on one page."""
    data = [[Paragraph(f'<font color="white">{c}</font>', cellb) for c in rows[0]]]
    data += [[Paragraph(c, cellb if i == 0 else cell) for i, c in enumerate(row)] for row in rows[1:]]
    t = Table(data, colWidths=[WIDTH * w for w in widths], repeatRows=1)
    t.setStyle(TableStyle([('VALIGN', (0, 0), (-1, -1), 'TOP'), ('LINEBELOW', (0, 0), (-1, -1), 0.5, LINE),
                           ('BACKGROUND', (0, 0), (-1, 0), BLUE),
                           ('LEFTPADDING', (0, 0), (-1, -1), 4), ('RIGHTPADDING', (0, 0), (-1, -1), 4),
                           ('TOPPADDING', (0, 0), (-1, -1), 4), ('BOTTOMPADDING', (0, 0), (-1, -1), 4)]))
    return [KeepTogether([t]) if len(rows) <= 9 else t, Spacer(1, 8)]


# ---------- images ----------

MARK_FONT = ImageFont.truetype(f'{FONT_DIR}/DejaVuSans-Bold.ttf', 40)


def jpeg(img, width, quality=80):
    img = img.convert('RGB')
    if img.width > width:
        img = img.resize((width, round(img.height * width / img.width)), PILImage.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, 'JPEG', quality=quality, optimize=True)
    buf.seek(0)
    return buf, img.size


def phone(name, cap, w, marks=()):
    """A screenshot (with numbered red markers at pixel positions) and its caption."""
    img = PILImage.open(os.path.join(SHOTS, name + '.png')).convert('RGB')
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, img.width - 1, img.height - 1], outline=(200, 205, 210), width=3)
    for n, (x, y) in enumerate(marks, 1):
        r = 32
        d.ellipse([x - r, y - r, x + r, y + r], fill=(179, 38, 30), outline='white', width=4)
        d.text((x, y), str(n), fill='white', font=MARK_FONT, anchor='mm')
    buf, (pw, ph) = jpeg(img, 600)
    return [Image(buf, width=w, height=w * ph / pw), Paragraph(cap, caption)]


def beside(left, name, cap, marks=()):
    """Text on the left, one screenshot on the right."""
    t = Table([[left, phone(name, cap, SHOT_W, marks)]], colWidths=[WIDTH - SHOT_W, SHOT_W])
    t.setStyle(TableStyle([('VALIGN', (0, 0), (-1, -1), 'TOP'), ('LEFTPADDING', (0, 0), (-1, -1), 0),
                           ('RIGHTPADDING', (0, 0), (0, -1), 12), ('RIGHTPADDING', (1, 0), (1, -1), 0),
                           ('TOPPADDING', (0, 0), (-1, -1), 0), ('BOTTOMPADDING', (0, 0), (-1, -1), 0)]))
    return [Spacer(1, 2), t, Spacer(1, 8)]


def pair(a, b):
    """Two screenshots side by side: (name, caption) each."""
    w = SHOT_W + 8
    t = Table([[phone(*a, w), phone(*b, w)]], colWidths=[w + 16, w])
    t.setStyle(TableStyle([('VALIGN', (0, 0), (-1, -1), 'TOP'), ('LEFTPADDING', (0, 0), (-1, -1), 0),
                           ('RIGHTPADDING', (0, 0), (0, -1), 16), ('RIGHTPADDING', (1, 0), (1, -1), 0),
                           ('TOPPADDING', (0, 0), (-1, -1), 0), ('BOTTOMPADDING', (0, 0), (-1, -1), 0)]))
    return [Spacer(1, 4), t, Spacer(1, 8)]


def slide(n, cap):
    path = next(f for f in (os.path.join(SLIDES, f'hi-{n}.png'), os.path.join(SLIDES, f'hi-{n:02d}.png')) if os.path.exists(f))
    img = PILImage.open(path)
    ImageDraw.Draw(img).rectangle([0, 0, img.width - 1, img.height - 1], outline=(190, 195, 200), width=2)
    buf, (pw, ph) = jpeg(img, 1200, 85)
    return [KeepTogether([Image(buf, width=WIDTH, height=WIDTH * ph / pw), Paragraph(cap, caption)]), Spacer(1, 8)]


# ---------- content ----------

def cover():
    title = ParagraphStyle('t', fontName='Sans-Bold', fontSize=24, leading=29, textColor=BLUE, spaceAfter=10)
    sub = ParagraphStyle('s', parent=body, fontSize=11, leading=15, textColor=GREY)
    return [
        Spacer(1, 70),
        P('Supplier Tracker', title),
        P('User guide', ParagraphStyle('t2', parent=title, fontSize=16, leading=20, textColor=colors.black)),
        Spacer(1, 8),
        P('Daily dispatch entry, month plan, a dashboard of every vendor and the PowerPoint review for management – '
          'and what to do if something goes wrong.', sub),
        Spacer(1, 24),
        P(f'Version {VERSION} · October 2026', small),
        P('All examples use made-up suppliers (Supplier A, B and C) and part numbers.', small),
        Spacer(1, 120),
        P('Your data stays on your phone. The app does not use the internet.', small),
    ]


def contents():
    toc = TableOfContents()
    toc.levelStyles = [toc1]
    return [PageBreak(), P('Contents', ParagraphStyle('c', parent=h1)), toc, PageBreak()]


def what():
    return [P('1. What the app does', h1),
            P('Each day you type how much every supplier dispatched. The app compares it with the plan and shows '
              'who is behind, by how much, and how much is needed per day to close the month. For any period – a week, '
              'a month or dates you choose – it makes a PowerPoint review for management.'),
            P('Everything is saved on this phone only. There is no server and no login – which is why backups '
              '(section 9) matter.'),
            *H2('The five tabs', *table([['Tab', 'Use it to'],
                    ['Entry', 'Type today\'s dispatch – one box per part – or import the vendor\'s Excel report. Every day.'],
                    ['Status', 'See one vendor: daily, weekly and monthly adherence, critical parts, each part with its '
                     'Day / Week / Month bars, reasons, dated notes, past months. Tap a part for its calendar.'],
                    ['Dashboard', 'The big round button in the middle; the app opens here. All vendors at a glance: '
                     'headline figures, 6-week trend, why parts are short. Build the PowerPoint for any period. Share '
                     'status on WhatsApp.'],
                    ['Plan', 'Type each part\'s month plan (or week by week) and the opening backlog. At month start, '
                     'and when the supplier\'s schedule changes.'],
                    ['Setup', 'Help, Settings, vendors, parts, days off, holidays, report template.']],
                   [0.22, 0.78])),
            *H2('At the top of every screen'),
            *bullets(['<b>Vendor</b> – tap to pick a vendor from the list.',
                      '<b>Date</b> – the day you are looking at. Tap ‹ or › to go a day back or forward, or tap the date '
                      'to pick any day. <b>Today</b> jumps back to today. The app always opens on today.']),
            *box('Most "wrong number" problems are the wrong date or the wrong vendor at the top. Check them first.')]


def first_time():
    return H1('2. First-time setup') + [
        P('Do this once, on the phone you will use every day.'),
        *steps([
            '<b>Install.</b> Open the file <b>SupplierTracker-x.y.apk</b> (from WhatsApp, Drive or Downloads). If Android '
            'asks, allow installs from that app, then tap <b>Install</b>. If Google Play Protect warns about an unknown '
            'developer, tap <b>More details → Install anyway</b>. Do not switch Play Protect off.',
            '<b>Open the app.</b> It opens on <b>Setup</b> because there are no vendors yet.',
            '<b>Load your data.</b> Tap <b>Import data file</b> and pick the data file you were given '
            '(<b>supplier-tracker-data.json</b>). Vendors, part numbers, addresses, plans and previous months appear. '
            'No file? Type a vendor name and tap <b>Add</b>, then add its parts (section 6).',
            '<b>Report template.</b> The app comes with your company template. Setup → <b>Report template '
            '(PowerPoint)</b> shows which one it uses; type your department for the slide footer there. To use '
            'another, tap <b>Import template</b> and pick the .pptx.']),
        *box('Uninstalling the app, or "Clear storage" in the phone\'s App info, deletes all your data. Your backup '
             'files are the only copy.', 'important'),
        *beside(steps([
            'Setup → <b>Open settings</b>.',
            '<b>Security</b> → turn on <b>Fingerprint lock</b>. The phone needs a fingerprint or screen lock in its '
            'own Settings first.',
            '<b>Auto backup</b> → turn on <b>Back up every night</b> and pick a folder (section 9).',
            'Optional: <b>Backup password</b> → <b>Set a password</b>.',
            '<b>Copy off the phone</b> → <b>Share backup</b> → save it to your Drive or send it to yourself.'], start=5),
            'settings-top', 'Settings: theme, text size, lock, backup'),
    ]


def daily():
    # marker pixel positions on the 822 x 1782 screenshot: vendor, date, box, status line
    marks = [(560, 80), (536, 202), (430, 652), (515, 734)]
    return H1('3. Every day: enter dispatch') + [
        P('Takes about two minutes. There is no Save button – every number is saved as you type.'),
        *beside(steps(['Open the <b>Entry</b> tab.',
                       'Pick the <b>vendor</b> (1) and check the <b>date</b> (2).',
                       'Type each part\'s dispatch in its box (3).',
                       'The box turns <b>green</b> (on schedule), <b>amber</b> (slightly behind) or <b>red</b> '
                       '(behind) against today\'s plan; the line under it says the gap (4).',
                       'Pick the next vendor and repeat.']) +
                [Spacer(1, 4),
                 P('Each part shows <b>Today plan</b>, <b>Week act/plan</b> (dispatched / plan this week) and '
                   '<b>Month act/req</b> (dispatched / required this month, backlog included). <b>Critical</b> marks '
                   'parts you flagged.', small)],
                'entry-typed', 'Red 150 against 200, green 100, amber 140 against 150', marks),
        *H2('What to type', *table([['Situation', 'Type this'],
                ['Supplier dispatched 1,500', '1500 or 1,500'],
                ['Supplier dispatched nothing', '<b>0</b> (zero). Do not leave it blank.'],
                ['You don\'t know yet', 'Leave it blank. It shows "Not entered" and the report lists it, so fill it '
                 'in later.'],
                ['Supplier sends a month-to-date total ("till-date")', 'The total so far this month. The app works '
                 'out today\'s quantity from the last total (see below).'],
                ['You typed a wrong number', 'Correct it in the same box. It saves at once.'],
                ['You typed it on the wrong day', 'Go to that day with ‹ ›, clear the box, then go to the right day '
                 'and type it there.'],
                ['Entering yesterday\'s dispatch today', 'Tap ‹ to yesterday first, then type.'],
                ['Supplier dispatched on a holiday or off day', 'Type it anyway. It counts as extra.']],
               [0.42, 0.58])),
        *H2('Suppliers who report till-date totals', *beside([P('Some suppliers only say "1,760 dispatched so far this '
                   'month". For them, Setup → <b>How this vendor reports</b> is set to <b>Till-date total</b>.'),
                 P('The box is then called <b>Till-date total</b>. Under it you see the last total and, once you type, '
                   '"= N dispatched today".')] +
                box('A till-date total never goes down. If you type a lower one – often 0 for "nothing today" – the box '
                    'turns red and says so; tap <b>Nothing today: use …</b> to keep the last total.', w=LEFT_W),
                'supplier-b-1-entry', 'Till-date supplier: type the month-to-date total')),
        *H2('Suppliers who dispatch once a week', *beside([P('Set their <b>Dispatch days</b> in Setup to just that day '
                   '(Supplier C: Saturday). The whole week\'s plan then falls on that day, and the supplier does not look '
                   'behind in the middle of the week.'),
                 P('On other days the Entry tab says it is not a dispatch day. If something does arrive, type it anyway '
                   '– it counts as extra.')],
                'supplier-c-1-entry', 'Weekly supplier on a day it does not dispatch')),
        *box('After 7 days without copying a backup off the phone, the Entry tab shows a reminder. Tap <b>Back up</b>.'),
        *H2('Or import the vendor\'s Excel report', *beside(steps([
                       'In WhatsApp or email, open the vendor\'s .xlsx file → <b>Share</b> → <b>Supplier Tracker</b>. Or: Entry '
                       '→ <b>Import file</b>, or Setup → <b>Import vendor file</b>.',
                       'The app finds the vendor from the part numbers, and the sheets with dispatch and plan (an invoice list, a '
                       'column per date, or a column per week). Check what it says under each.',
                       'Scroll down: days, parts and pieces, every figure that differs from what is in the app, and plan '
                       'changes. Untick what you do not want.',
                       'Tap <b>Save</b>. <b>Undo this import</b> puts everything back, until you change something else.']),
                'import-top', 'The vendor and the sheets, found by the app')),
        *pair(('import-check', 'Check: what differs from the app'), ('import-done', 'Saved, with Undo')),
        P('The first time, check the columns (<b>Wrong column? Change it</b>). The app remembers each vendor\'s format, so '
          'next week\'s file goes straight to the check. A till-date supplier\'s invoices become till-date totals by '
          'themselves; a weekly report puts each week\'s figure on its last dispatch day.'),
        *box('The app reads Excel (.xlsx) and CSV files – not pictures. If a vendor pastes a screenshot of their sheet into '
             'the email, ask them to attach the Excel file instead. Old .xls files: open in Excel and save as .xlsx.'),
    ]


def weekly():
    return H1('4. Plan and weekly routine') + [
        *H2('The plan: type the month total once', *beside(steps(['Open <b>Plan</b> and pick the vendor. '
                       '<b>Whole month</b> is selected.',
                       'Type each part\'s total for the month from the supplier\'s schedule, then tap Next.',
                       'The line under the box shows how it was split into the weeks (W40, W41 …).',
                       'Repeat for each vendor.']),
                'supplier-a-3-plan', 'Plan tab: month plan per part, split into weeks')),
        P('The month total is split into the weeks by the vendor\'s dispatch days, skipping holidays and the '
          'supplier\'s own days off, in whole pieces. Weeks are cut at the month end: <b>W40 1–4</b> is only the '
          'October days of week 40.'),
        P('<b>Schedule changed?</b> Type the new month total. The change goes to the weeks to come: a week already '
          'under way (✓) keeps its plan, so the days already gone never change. The part shows "Original 7,000, '
          're-planned +600". Reports use the latest plan.'),
        P('<b>Week by week</b> (the switch at the top) shows one week at a time: change a single week there, or tap '
          '<b>Copy last week</b>. The carried backlog is never typed into the plan; it comes on top '
          '(section 5).'),
        *H2('Check each vendor on the Status tab', *beside([P('At the top: <b>Daily</b>, <b>Weekly</b> and <b>Monthly '
                   'adherence</b> (dispatched ÷ plan today, this week to date, and this month to date with the backlog) '
                   'and <b>Critical parts</b> not on schedule.'),
                 P('<b>Parts details</b> lists every part worst first. Tap <b>Bad</b> for the parts behind, slightly behind '
                   'or not entered, <b>Good</b> for those on schedule, or <b>Critical</b>.'),
                 P('Each part has three bars – <b>Day</b>, <b>Week</b> and <b>Month</b> plan against dispatch – green on '
                   'schedule, amber slightly behind, red behind, like the Entry box.')],
                'status-top', 'Status: adherence and Parts details')),
        *H2('One part: calendar, weeks and months', *beside([P('Tap a part (or <b>Calendar ›</b>). The <b>Calendar</b> '
                   'shows the month: each day has what was dispatched (big) over its plan (small), coloured the same way. '
                   'Tap a day for its plan, dispatch, gap and notes.'),
                 P('<b>Weekly</b> shows each week of the month, plan to date against dispatch. <b>Monthly</b> shows the '
                   'last six months. ‹ › change the month. Back returns to Status.')],
                'part-calendar', 'A part\'s calendar')),
        *pair(('part-weekly', 'Weekly view'), ('part-monthly', 'Monthly view')),
        *H2('Notes by date', *beside([P('Status → <b>Notes</b> → <b>+ Add note</b> files a note under the date at the '
                   'top; on a part\'s page it goes under the day you tapped. Notes are listed by date, newest first, so '
                   'you can see what was said on which day. <b>Edit</b> or <b>Delete</b> any of them.'),
                 P('Reasons show the day they were added. <b>Edit</b> changes the cause, action, owner or due date.')],
                'status-notes', 'Notes by date')),
        *H2('Write down why a part is behind', *beside(steps(['On Status, tap <b>+ Reason</b> under the part.',
                       'Pick the <b>Gap cause</b>, type the <b>Action</b> agreed with the supplier and the '
                       '<b>Owner</b>, and set a <b>Due date</b>.',
                       'Tap <b>Save reason</b>.',
                       'When it is resolved, tap <b>Close</b> next to the reason.']),
                'reason-form', 'Logging a reason')),
        *H2('Friday or Saturday: the review', *beside(steps(['Open <b>Dashboard</b> and pick the period: <b>This week</b>, '
                       '<b>Month to date</b>, <b>Last month</b> or <b>Custom range</b>.',
                       'Check the figures, the 6-week trend and why parts are short.',
                       'Scroll down, tap <b>Build report (PowerPoint)</b> and share it to email, WhatsApp or Drive. '
                       'Open it once in PowerPoint before you send it.']) +
                box('Log reasons before you build the report. Parts behind without a reason are listed as '
                    '"Reason not given".', w=LEFT_W),
                'dashboard', 'Dashboard: period, figures, trend')),
    ]


def month_end():
    return H1('5. Month end') + [
        *steps(['Make sure every day of the month is entered – no "Not entered" left.',
                'Dashboard → <b>Last month</b> → <b>Build report</b> for the month\'s review.',
                'On the 1st, type the new month\'s plan on the Plan tab: one total per part (tap › for the next '
                'month if the date at the top is still in the old month).',
                'Check the opening backlog at the bottom of the Plan tab.']),
        P('<b>Opening backlog</b> is what was still pending at the end of last month. The app carries it forward '
          'automatically ("auto"). Type a number only if you agreed a different figure with the supplier; clear the '
          'box to go back to automatic.'),
        *beside([P('Finished months appear on the Status tab under <b>Past months</b>, with the month\'s status. Tap '
                   '<b>Parts</b> to see each part\'s dispatch against the plan.'),
                 P('For Excel: Dashboard → <b>Summary CSV</b> (one line per part for the month) or '
                   '<b>Dispatch log CSV</b> (every entry).')],
                'supplier-a-2c-past-month-parts', 'Past months, part-wise'),
    ]


def vendors():
    return H1('6. Vendors, parts and days off') + [
        *H2('Add a vendor'),
        *steps(['Setup → <b>Vendors</b> → type the name → <b>Add</b>.',
                'Pick the new vendor at the top and fill <b>Contact</b>, <b>Address</b> and <b>Notes</b>.',
                'Set <b>How this vendor reports</b>: <b>Daily quantity</b> or <b>Till-date total</b>.',
                'Set the <b>Dispatch days</b>. A supplier who dispatches once a week gets only that day.']),
        *H2('A supplier\'s own days off', *beside([P('Setup → pick the vendor → <b>Days off for …</b> → '
                   '<b>+ Add days off</b>. Tap one day, or the first and last day of a shutdown, then OK.'),
                 P('No plan is expected on those days; the week\'s plan moves to the supplier\'s other dispatch days. '
                   'Other vendors are not affected. Tap ✕ on a chip to remove it.'),
                 P('<b>Holidays (all vendors)</b>, further down, are plant holidays for everyone.')],
                'setup-days-off', 'Supplier C: a day off and a shutdown')),
        *H2('Add a part', *beside(steps(['Pick the vendor at the top, then Setup → <b>Parts</b> → <b>+ Add part</b>.',
                       'Type the <b>Part no.</b> and <b>Description</b>. Tick <b>Critical</b> if a shortage stops '
                       'the line.',
                       'Type its month plan on the Plan tab.']),
                'setup-parts', 'Parts, vendors and holidays in Setup')),
        *H2('Stopping a part or a vendor', *table([['Action', 'What happens'],
                ['Untick <b>Active</b>', 'Hidden from Entry and Plan; its history stays. Tick it again any time. '
                 'Best for parts that pause.'],
                ['<b>Remove</b> a part', 'Gone from all lists. Its old figures stay only in older backups.'],
                ['<b>Delete vendor…</b>', 'Deletes the vendor with all its parts, plans, dispatch and reasons. Back up '
                 'first.']],
               [0.3, 0.7])),
    ]


def numbers():
    return H1('7. Reading the numbers') + [
        *table([['Term', 'Meaning'],
                ['Plan', 'The weekly quantity, spread evenly over the vendor\'s dispatch days.'],
                ['Backlog in', 'Pending quantity carried into the first day of the period.'],
                ['Period requirement', 'Backlog in + the plan for the whole period.'],
                ['Required to date', 'Backlog in + plan for the days up to the as-of date.'],
                ['Adherence', 'Dispatched ÷ required to date.'],
                ['Daily adherence', 'Today\'s dispatch ÷ today\'s plan.'],
                ['Weekly adherence', 'This week\'s dispatch ÷ this week\'s plan to date (no backlog).'],
                ['Monthly adherence', 'This month\'s dispatch ÷ backlog in + plan to date.'],
                ['Short to date', 'Required to date − dispatched (when behind).'],
                ['Gap', 'Dispatched − plan. Negative (red) means behind.'],
                ['Need per day', 'What is still needed in the period ÷ dispatch days left.'],
                ['Days gone', 'Share of the period\'s dispatch days already passed.'],
                ['Coverage', 'Dispatched ÷ period requirement – how much of the period is covered so far.'],
                ['Week-on-week trend', 'On the Dashboard: each week\'s dispatch ÷ that week\'s plan, without backlog.']],
               [0.32, 0.68]),
        *H2('Status and colours', *table([['Status', 'Colour', 'When'],
                ['On schedule', 'Green (G)', 'Adherence 95% or more'],
                ['Slightly behind', 'Amber (A)', 'Adherence 85% to 95%'],
                ['Behind', 'Red (R)', 'Adherence below 85%'],
                ['Not entered', 'Grey (–)', 'Plan exists but nothing typed yet'],
                ['No plan', 'Grey (–)', 'No plan for this period'],
                ['Ahead', 'Green', 'Dispatched without any plan']],
               [0.3, 0.2, 0.5])),
        P('The same colours are used for the Entry box, the bars on Status and Dashboard, and the RAG in the '
          'PowerPoint.'),
        *box('Quick check: if <b>Coverage</b> is lower than <b>Days gone</b>, the vendor is behind the calendar. '
             'Example: 27% of the month gone but only 20% delivered.'),
    ]


def report():
    return H1('8. The PowerPoint review') + [
        P('Built on the phone from your company template, for the period chosen on the Dashboard. Figures count up to '
          'the date at the top (or the end of the period once it has passed).'),
        *table([['Slide', 'Shows'],
                ['Title', 'Your template\'s title slide, with the period and "status as of" date.'],
                ['Supply status – all vendors', 'One row per vendor, coloured by RAG: parts short, backlog, '
                 'requirement, delivered, adherence, days gone, coverage, short, need/day.'],
                ['Time gone vs deliveries', 'For each vendor: share of the period delivered (bar in RAG colour) '
                 'against share of days gone (line).'],
                ['Week-on-week adherence', 'Last 6 weeks for every vendor, coloured green / amber / red, with the '
                 'direction.'],
                ['Parts short – all vendors', 'Every part red or amber (or not entered), grouped by vendor, with the '
                 'reason.'],
                ['Parts on schedule – all vendors', 'Every part on schedule, grouped by vendor.'],
                ['Backlog details', 'Every part with backlog or a shortfall.'],
                ['Why parts are short', 'Gap causes by pieces short, biggest first, with the running share.'],
                ['Reasons and actions', 'Open reasons with action, owner and due date; then parts behind without a '
                 'reason.'],
                ['Closing', 'Your template\'s closing slide.']],
               [0.36, 0.64]),
        *slide(2, 'Supply status – all vendors (sample data, built-in template)'),
        *slide(4, 'Week-on-week adherence'),
        *slide(5, 'Parts short – all vendors'),
        *slide(8, 'Why parts are short – gap causes'),
        *H2('Tips'),
        *bullets(['Enter the whole period and log reasons first; the report only knows what is in the app.',
                  'Your company template gives the title and closing slides, logo, colours and footer. Its own sample '
                  'and reference slides (colours, symbols) are left out.',
                  'If the report fails with your template, tap <b>Use built-in</b> in Setup and try again.']),
    ]


def backup():
    return H1('9. Backups, new phone and updates') + [
        *H2('Night backup (automatic)', *beside([P('Setup → <b>Open settings</b> → <b>Auto backup</b> → turn on '
                   '<b>Back up every night</b> and pick a folder (for example Documents › Supplier Tracker).'),
                 P('Every night at about 2:30 AM the phone saves <b>supplier-tracker-auto-DATE.json</b> there – the '
                   'app does not need to be open. The last 14 nights are kept. Settings shows the last backup; '
                   '<b>Back up now</b> makes one straight away.')] +
                box('Night backups stay on this phone. They protect you if the app\'s data is lost, not if the phone '
                    'is lost – unless the folder syncs to the cloud.', w=LEFT_W),
                'settings-backup', 'Auto backup and backup password')),
        *H2('Copy a backup off the phone every week'),
        P('Settings → <b>Copy off the phone</b> → <b>Share backup</b> and save the file to your Drive or send it to '
          'yourself. <b>Save to phone</b> keeps it in the phone\'s storage.'),
        *H2('Backup password'),
        P('Settings → <b>Backup password</b> → <b>Set a password</b> (6 or more characters, typed twice). From then on '
          'every backup – night, shared or saved – is locked with AES-256 encryption; the file cannot be read without '
          'the password.'),
        *box('Nobody can open a locked backup without its password – not even you. Write it down somewhere safe. '
             'Without a password the file holds vendor names, part numbers and quantities in plain text: keep it in '
             'your own or company storage and never post it in a group.', 'important'),
        *H2('Restore a backup'),
        P('Settings → <b>Import / restore from file</b> → pick the backup. A locked backup asks for its password. '
          'Then tap <b>Replace</b>: everything on the phone is replaced by the file.'),
        *H2('Moving to a new phone'),
        *steps(['On the old phone: Settings → <b>Share backup</b>.',
                'On the new phone: install the APK (section 2).',
                'Tap <b>Import data file</b>, pick the backup and type its password if it has one.',
                'Import the report template again and set Settings (lock, night backup, password, theme). These '
                'are phone settings and are not in the backup.']),
        *H2('Installing a new version'),
        *steps(['Take a backup first.',
                'Open the new APK and tap <b>Update</b>. Your data and settings stay.',
                'If Android says <b>App not installed</b> or that the package conflicts, the new file was signed '
                'differently. With your fresh backup safe: uninstall the old app, install the new one, then '
                '<b>Import data file</b> → pick the backup.']),
    ]


def appearance():
    return H1('10. Theme and text size') + [
        *beside([P('Setup → <b>Open settings</b> → <b>Appearance</b>:'),
                 *bullets(['<b>Theme</b>: Phone (follows the phone), Light or Dark.',
                           '<b>Colours</b>: the app\'s blue, or your phone\'s wallpaper colours (Android 12+).',
                           '<b>Text size</b>: Small, Normal, Large or Extra large – on top of the phone\'s own font '
                           'size. The preview shows the size before you leave.']),
                 P('Status colours (green, amber, red) stay the same in every theme.')],
                'entry-dark-large', 'Dark theme, extra large text'),
    ]


def trouble():
    return H1('11. If something goes wrong') + [
        P('Find your problem on the left. Try the steps in order.'),
        *table([['Problem', 'What to do'],
                ['Numbers look wrong', 'Check the date and vendor at the top. Check Setup → How this vendor reports. '
                 'Check the week\'s plan and the opening backlog on the Plan tab.'],
                ['"No plan" on a part', 'No plan for this week: Plan tab → type the month total. "No plan today" '
                 'also shows on holidays, the supplier\'s days off and days it does not dispatch.'],
                ['"Not entered"', 'Nothing typed yet. If the supplier sent nothing, type 0.'],
                ['Typed 0 but the box is not red', 'With no plan today the box is red only when the part is short to date; '
                 'otherwise it says "No plan today". Check the week\'s plan on the Plan tab.'],
                ['"Lower than the last till-date total"', 'For a till-date supplier type the month total so far, not '
                 'today\'s quantity. Nothing today? Tap <b>Nothing today: use …</b>.'],
                ['Supplier says all was sent, app shows behind', 'Go back day by day and compare with the supplier\'s '
                 'list. For a till-date supplier the box needs the month total, not today\'s quantity.'],
                ['Negative quantity for a till-date supplier', 'A total is lower than the day before. Correct the '
                 'wrong total; Status → tap the part → Calendar shows the day.'],
                ['A part is missing on Entry', 'Setup → Parts: is it ticked Active? Is the right vendor picked?'],
                ['Weekly supplier always "Behind" mid-week', 'Setup → Dispatch days: switch on only its dispatch day.'],
                ['Fingerprint not accepted', 'Use the phone PIN option on the same screen. Check the fingerprint in the '
                 'phone\'s Settings → Security.'],
                ['Fingerprint switch is grey', 'The phone has no screen lock. Set a PIN and fingerprint in the phone\'s '
                 'Settings.'],
                ['Red banner "Could not read the saved data"', 'Do not uninstall. Settings → Import / restore from file → '
                 'pick your latest backup (night backups are in your backup folder). Then report the problem.'],
                ['Import says the file is not valid', 'Pick the .json backup or data file – not a CSV, PowerPoint or a '
                 'file renamed by WhatsApp. Download it again if needed.'],
                ['Vendor file: "None of … part numbers are in this file"', 'Pick the right vendor at the top of the import. '
                 'The part numbers in Setup must match the vendor\'s (spaces and dashes do not matter).'],
                ['Vendor file: a figure is wrong', 'Check the sheet and the columns under "Dispatch from" (Wrong column? '
                 'Change it). Saved already? Undo this import, or correct the day on Entry.'],
                ['"Wrong password" on a locked backup', 'Check capitals and spaces. A forgotten password cannot be '
                 'recovered: use an older backup made without it.'],
                ['"Last backup failed" in Settings', 'Auto backup → Change → pick the folder again, then Back up now. '
                 'If the folder was on a removed SD card, pick another one.'],
                ['No night backup appears', 'Check Auto backup is on. Some phones stop background work to save '
                 'battery: phone Settings → Apps → Supplier Tracker → Battery → Unrestricted.'],
                ['"Could not build the report"', 'Setup → Report template → Use built-in, then try again. Re-import the '
                 'template (.pptx only). If it still fails, report the problem.'],
                ['Report looks odd in WhatsApp preview', 'Previews are rough. Open it in PowerPoint or on a laptop.'],
                ['Share does nothing', 'Install Google Drive or a Files app, or use Save to phone.'],
                ['Guide will not open in a PDF app', 'Use Read user guide in the app, or Share guide to Drive.'],
                ['Text too small or too big', 'Settings → Text size.'],
                ['App closes or freezes', 'Open it again – what you typed is already saved. A red box at the top offers '
                 '<b>Send crash report</b>: send it to whoever gave you the app (it has no supplier data).'],
                ['Version 1.3 closed when picking a folder or a file', 'Fixed in 1.4. Install the update.'],
                ['Keyboard hides the tabs', 'Normal: the tabs hide while you type. Press back or Done.'],
                ['Today\'s date is wrong', 'The app uses the phone\'s date. Check phone Settings → Date & time.'],
                ['Play Protect warning while installing', 'More details → Install anyway. Do not switch Play Protect '
                 'off.']],
               [0.38, 0.62]),
    ]


def support():
    return H1('12. Getting help') + [
        *beside(steps(['Take a backup first, so nothing is lost if the fix needs a reinstall.',
                       'Take a screenshot of the problem (Power + Volume down).',
                       'Setup → <b>Help & support</b> → <b>Report a problem</b>. Send it by email or WhatsApp to '
                       'whoever gave you the app.',
                       'Fill in what happened, what you expected and what you tapped just before. Attach the '
                       'screenshot.']) +
                [P('The report adds the app version, phone model, Android version, night backup status and how many '
                   'vendors, parts and entries you have – no supplier names or quantities.', small),
                 P('<b>Quick fixes</b> in the same card lists the common problems and their fixes.', small)],
                'setup-quick-fixes', 'Help & support, with quick fixes open'),
        *box('Never send your backup file outside the company to get help.', 'important'),
    ]


def limits():
    return H1('13. Good to know') + [
        P('The app is a dispatch tracker. Keep these limits in mind when you read its numbers:'),
        *bullets(['It records what the supplier <b>dispatched</b>, not what reached the plant. Parts in transit are '
                  'counted as delivered.',
                  'It has no rejection or quality entry. If parts are rejected, log it as a reason.',
                  'The plan is spread evenly over dispatch days. A supplier who sends in lots on fixed days looks '
                  'behind on the other days.',
                  'One phone, one person. There is no sync between phones.',
                  'Night backups stay on the phone unless the folder syncs to the cloud: still copy one off weekly.',
                  'For a re-planned week only the original and the latest plan are kept.',
                  'Check with your IT team that this app is allowed on your work phone.']),
        Spacer(1, 10),
        P(f'Supplier Tracker {VERSION}. Your data stays on this phone; the app has no internet permission.', small),
    ]


def main():
    doc = Guide(OUT)
    story = [NextPageTemplate('page')] + cover() + contents()
    for part in (what, first_time, daily, weekly, month_end, vendors, numbers, report, backup, appearance, trouble, support, limits):
        story += part()
    doc.multiBuild(story)
    print(OUT, os.path.getsize(OUT) // 1024, 'KB')


if __name__ == '__main__':
    main()
