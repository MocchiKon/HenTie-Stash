# HenTie — your local comics library

HenTie is a small website that runs **on your own computer** for browsing and managing your
comics collection. You open it in a normal web browser. Other devices on your home network
(phone, tablet, another PC) can open it too.

There is no internet account, no cloud, no installation wizard — just one program file.

---

## 1. Running it

You need **Java 21 (or newer)** installed once. If you don't have it, download the free
"Temurin 21" installer from <https://adoptium.net/> and run it.

Then, in the folder that contains the program:

| You are on… | Do this |
|-------------|---------|
| **Windows** | Double-click **`HenTie.exe`** (or **`start.bat`**) |
| Anything else | Double-click **`HenTie.jar`**, or run `java -jar HenTie.jar` in a terminal |

A black window may appear — **leave it open** while you use the app (closing it stops the app).
Your browser should open automatically at <http://localhost:8080>. If it doesn't, open that
address yourself.

### Logging in
Out of the box there is **no password**: anyone on your network can use the app. To require one, tick
**Require login to access the app** on the **Settings** page — it asks you to choose a password before
login takes effect. From then on the app asks only for that password.

Forgot it? Close the app, delete the file **`password-delete-to-reset.txt`** next to it, and start it
again. The app then starts with login turned off; set a new password in **Settings** to turn it back on.

### Opening it from your phone / another device
1. Make sure the device is on the **same Wi-Fi / network** as this computer.
2. Find this computer's address: open a terminal here and run `ipconfig` (Windows) — look for the
   "IPv4 Address", e.g. `192.168.1.20`.
3. On the other device's browser, go to `http://192.168.1.20:8080` (use your own number).

---

## 2. Where your images live

Images are **not** stored in the program. They live in a folder called **`data`** next to the
program file:

```
HenTie.jar
bin/            ← the image tools used by Image Compression (see below)
data/
  51252/        ← a chapter's id number
    1.jpg       ← page 1 (also used as the cover/thumbnail)
    2.jpg       ← page 2
    3.png
    ...
```

- One folder per chapter, named with the chapter's **id** (shown on the chapter page).
- Each image's **file name is its page number** (`1.jpg`, `2.jpg`, …). The lowest number is page 1
  and is used as the thumbnail.
- Files that aren't numbered (for example a `thumbnail.jpg` left by a scraper) are **ignored** —
  they won't show up as pages.

You can add or remove images from the **Edit chapter** page, or just drop files into the folder.

The **`bin`** folder holds the image tools (ImageMagick, `cjxl`, `djxl`, `avifenc`) that Image
Compression uses. Keep it next to the program. Without it the app still works — images are simply
stored as they arrive.

---

## 3. What you can do

- **Search** (the home page): find chapters or series. Flip the toggle at the top between
  *Chapters* and *Series*. Type to get suggestions for tags, artists, characters, parodies, groups,
  categories (doujinshi, manga, …) and languages; each pick becomes a removable tag. Picking several of the same kind means
  "must have all of them". Filter by title, rating (½–5 stars), upload date and status.
- **Open a chapter**: see its details and all its pages. Click a page to **read** it.
- **Reading view**: click the right/left side of the image (or press **D / →** and **A / ←**) to
  move between pages. Buttons at the bottom: back to the chapter (←), the page as stored (with a
  ComfyUI workflow, to compare - also **S**), previous `<`, page counter, next `>`, *Hide UI*, and the
  cogwheel with *Fit* vs *Original* size and the workflow. Press **H** to bring the bar back, **B** to go
  back to the chapter. The next pages are prepared while you read - how many is set in **Settings**.
  On the last page, going on once says which chapter comes next and going on again opens it (the next
  chapter of the same series, in the same language); going back on the first page opens the previous
  chapter at its last page.
- **Series**: group chapters together. A series shows its chapters and lets you pick which language
  to display. If you don't fill in a series' tags/artists/etc., they are gathered automatically from
  its chapters (with counts). **Link chapters** on a series finds the chapters that probably belong in
  it, among chapters in no series or among all of them, and links the ones you tick.
- **Edit**: change details, add/remove images, link a chapter to a series, create new series.
- **Manage**: rename, delete or **merge** tags / artists / characters / parodies / groups / categories across
  your whole library.
- **Image Compression**: shrink your pages by downscaling and re-encoding them to JPEG XL or AVIF.
  Pick a mode — *None* (the default, keeps everything exactly as it arrived), *Lossless*, *High
  reduction*, *Very high reduction*, or one you define yourself — in **Settings** (for images you
  upload, and as the starting choice on the **Download** page) and on the **Download** page (for images
  that are downloaded, chosen per paste). A page is
  only replaced when it actually got enough smaller, and anything that goes wrong simply keeps the
  original. The tools ship with the app in the `bin` folder; tick *"Use the image tools installed on
  this system"* to use your own instead. A chapter whose pages were compressed says so on its page
  (*Compressed: Lossless*, for example), and if it was downloaded, **Re-download in full quality** fetches
  its pages again from the source, uncompressed — the compressed ones are replaced only once the
  originals have arrived.
- **ComfyUI workflows** (optional): read any chapter through a [ComfyUI](https://github.com/comfyanonymous/ComfyUI)
  workflow of your own — upscaling, restoration, edits with diffusion models. Pick the workflow in the
  reading view's cogwheel menu, or a default in **Settings**; your files are never changed, and each page is
  processed once per workflow. The app can start ComfyUI for you when it starts. Installing ComfyUI,
  exporting workflows for the app and keeping SSD writes down are explained in [COMFYUI.md](COMFYUI.md).
- **Download** (Manage → Chapters → Download): paste links, one per line — nhentai gallery links
  (`https://nhentai.net/g/123456/`, or just `nhentai:123456`) — and they are downloaded in the background,
  one at a time; **View download queue** shows how far it got and what failed. **Download all favourites**
  queues every gallery in your nhentai favourites that is not in your library yet; it needs your nhentai
  API key (see Settings). A gallery's artists written as `name1 | name2` become two artists.
- **Subscriptions** (Manage → Chapters → Subscriptions): follow a search on nhentai, e-hentai or exhentai —
  e.g. an artist, or a tag in your language. The app queues every gallery the search already has, newest
  first, then checks for new ones as often as you choose (every 10 minutes at the most), and once a day it
  looks at the last two days again, for galleries that were tagged after they were uploaded. It never queues a
  gallery you already have or downloaded before (even if you deleted it since). What a subscription queues
  downloads after the links you paste, and it keeps only about a hundred galleries waiting at a time, so it
  goes at the pace of your downloads. exhentai needs your e-hentai account's cookies in Settings.
- **Settings**: turn the login requirement on/off, choose the default reading size, change your
  password, store your nhentai API key (create one in your nhentai account settings, under API keys) and
  your e-hentai account's cookies (for exhentai subscriptions), and choose how JPEG XL pages are sent to your
  browser (some browsers cannot show them, so they are turned into PNG on the way out).

Every **delete** asks you to confirm first.

---

## 4. Backups & data

- Your library database is the **`db`** folder. Your images are the **`data`** folder.
- To back up everything, copy those two folders somewhere safe while the app is **closed**.

The library database is a single SQLite file (`db/mydbNew.db`). Advanced users can inspect it with
any SQLite client (e.g. the `sqlite3` CLI or [DB Browser for SQLite](https://sqlitebrowser.org))
while the app is **closed**.

Upgrading from an older **H2**-based build? See [migration/MIGRATION.md](migration/MIGRATION.md) for the
one-time H2 → SQLite data-migration steps.

---

## 5. Building it yourself (optional, for developers)

```
./mvnw clean package
```

This produces `target/HenTie.jar` (runs anywhere with Java) and, on Windows, `target/HenTie.exe`.
See [CLAUDE.md](CLAUDE.md) for the architecture and developer notes.
