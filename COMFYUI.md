# Processing pages with ComfyUI

The image viewer can show every page through a **ComfyUI workflow** of your own — upscaling,
restoration, sharpening, colouring, edits with diffusion models: anything ComfyUI can do to an image.
You build the workflow in ComfyUI once; the app then runs it on each page you read.

- **Nothing in your library is changed.** The files in `data/` stay exactly as they are; the viewer
  shows a processed copy. Pick *None* and you see the pages as stored again.
- **A page is processed once per workflow.** The result is kept (see
  [Keeping disk writes down](#8-keeping-disk-writes-down)), so turning back to a page is instant.
- **ComfyUI runs on your own computer** (or another machine you control). The app talks to its local
  web API; your phone or tablet only ever talks to the app, behind the app's own login.

ComfyUI is a separate program. It has to be installed once and **running while you read with a
workflow** — the app can start it for you (section 3).

---

## 1. What you need

| Your graphics card | Speed |
|---|---|
| NVIDIA (GeForce / RTX / Quadro) | Fast |
| AMD Radeon or Intel Arc | Usually fast, depending on the workflow and drivers |
| None, or integrated graphics only | Upscaling works but is slow (from ~30 seconds to minutes per page). Diffusion workflows are impractically slow. |

The integration was built against ComfyUI 0.37; any recent version should work.

## 2. Install ComfyUI

Use whichever installation suits you — the app does not care how ComfyUI is installed, only at which
address it answers. The official instructions are at
<https://github.com/comfyanonymous/ComfyUI#installing>.

| Installation | Typical start | Address | User folder (see section 5) |
|---|---|---|---|
| **Windows portable** (a zip for NVIDIA, AMD or CPU) | `run_nvidia_gpu.bat` / `run_cpu.bat` | `http://127.0.0.1:8188` | `ComfyUI_windows_portable\ComfyUI\user\` |
| **Manual install** (git + Python venv), Windows or Linux | `python main.py` | `http://127.0.0.1:8188` | `ComfyUI/user/` |
| **comfy-cli** (`pip install comfy-cli`) | `comfy launch` | `http://127.0.0.1:8188` | `<workspace>/ComfyUI/user/` |
| **ComfyUI Desktop** (Windows / macOS app) | its own window | `http://127.0.0.1:8000` | `user\` inside the folder you chose when installing |

Start ComfyUI once by hand and open its address in a browser: that is where you build workflows.

## 3. Let the app start ComfyUI (optional)

In **Settings → ComfyUI**, tick **Start ComfyUI when the app starts** and enter the **start script** —
a script in your ComfyUI installation that starts it. The app then:

- starts it in the background when the app starts, **unless something already answers** at the address
  (a ComfyUI you started yourself is used as it is and never touched);
- **stops it when the app exits** — the script and everything it started, so no Python process is left
  holding your GPU. If the app was killed rather than closed, it stops that ComfyUI the next time it
  starts;
- shows its status and its last lines of output under **Settings → ComfyUI status**, with *Start* and
  *Stop* buttons. That is where a start that fails explains itself.

Any `.bat`, `.cmd`, `.ps1` or `.exe` works on Windows, and on Linux an executable script with a `#!`
line (`chmod +x run_for_hentie.sh`) - the app runs it directly, never through a shell. The script runs
in its own folder, and it **must not be inside the app's data folder or a temporary-files folder**: those
hold what is uploaded and downloaded, and nothing there is ever run.

**Write a script of your own for the app** rather than using the launcher you double-click:

- add **`--disable-auto-launch`**, or every start opens a browser tab (the Windows portable launchers
  pass `--windows-standalone-build`, which opens one);
- keep ComfyUI **in the foreground**: no `start` on Windows, no `&` or `nohup` on Linux. The app can
  only stop what the script is still running - a script that returns while ComfyUI goes on is shown as
  *Running, but the start script let go of it*, and you stop that ComfyUI yourself;
- add the flags from section 8 if you want to spare your SSD.

A `pause` at the end is harmless.

**Windows portable** — `run_for_hentie.bat`, next to `run_nvidia_gpu.bat`:

```bat
@echo off
.\python_embeded\python.exe -s ComfyUI\main.py --windows-standalone-build --disable-auto-launch
```

(add `--cpu` for a computer without a supported graphics card)

**Windows, manual install with a venv** — `run_for_hentie.bat` in the ComfyUI folder:

```bat
@echo off
call venv\Scripts\activate.bat
python main.py --disable-auto-launch
```

**Linux** — `run_for_hentie.sh` in the ComfyUI folder, made executable with `chmod +x`:

```sh
#!/bin/sh
cd "$(dirname "$0")"
. venv/bin/activate
exec python main.py --listen 127.0.0.1 --port 8188
```

`exec` lets ComfyUI take the script's place, so stopping it is exact. With conda, replace the two
middle lines by `exec conda run --no-capture-output -n <env> python main.py ...`.

**ComfyUI Desktop** starts its own server when its window opens; point the start script at
`ComfyUI.exe` if you want the app to open it, or leave autostart off and open it yourself. Either way,
set the address to `http://127.0.0.1:8000`.

## 4. Build a workflow

A workflow is a chain of nodes: one loads the image, others process it, one shows the result. The app
puts its page into your workflow and takes the result back. It needs to know where:

- **Input:** the **Load Image** node. If the workflow has more than one, rename the one that should
  receive the page to **`Input`** (double-click a node's title to rename it).
- **Output:** the **Save Image** or **Preview Image** node. If there is more than one, rename the one
  with the final result to **`Output`**.

Everything else runs **exactly as you exported it**: models, strengths, prompts — and seeds. A
*randomize* seed setting only applies inside ComfyUI, so the same page always gives the same result
(which is also what makes keeping results correct). Only the branch that leads to the Output node is
run, so extra Preview nodes you keep for looking at intermediate steps cost nothing. A workflow that
produces several images (a batch) shows the first.

For a simple upscaler: double-click the empty canvas to search for nodes and connect **Load Image →
Load Upscale Model → Upscale Image (using Model) → Save Image**. Put the model files into the matching
folder of ComfyUI's `models` folder (upscale models such as ESRGAN or DAT from
[openmodeldb.info](https://openmodeldb.info) go into `models/upscale_models/`; checkpoints into
`models/checkpoints/`; LoRAs into `models/loras/`) and press **R** in ComfyUI to see them. Pick any
test image in the Load Image node and **Run** it once in ComfyUI to check that it works.

Pages stored as JPEG XL are sent to ComfyUI as PNG, since ComfyUI cannot read JPEG XL. Every other
format the app stores is sent as it is.

## 5. Export it for the app

In ComfyUI's menu choose **File → Export (API)** (older versions: enable *Dev mode* in the settings,
then use *Save (API Format)*). Use the API export — a normal *Save* or *Export* produces a file the app
cannot run, and Settings says so.

Put the exported `.json` file into the **workflow folder**, which by default is `api_workflows` in
ComfyUI's user folder for the `default` user:

| Installation | Folder |
|---|---|
| Windows portable | `ComfyUI_windows_portable\ComfyUI\user\default\api_workflows\` |
| Manual install / comfy-cli | `ComfyUI/user/default/api_workflows/` |
| ComfyUI Desktop | `user\default\api_workflows\` inside the folder you chose when installing |
| Started with `--user-directory <dir>` | `<dir>/default/api_workflows/` |

Create the folder if it does not exist. The app reads it through ComfyUI's API, so it works the same
when ComfyUI runs on another machine.

- The file name without `.json` is the workflow's name. Subfolders are listed too:
  `api_workflows/upscale/4x.json` appears as `upscale/4x`.
- To change a workflow, export it again under the same name. Pages processed with the old version are
  processed again when you view them.
- **Settings → ComfyUI** lists every workflow found, with the reason next to any the app cannot run —
  click the *Workflows* line, which says how many are ready, to see the list. A different folder (for
  example `api_workflows/hentie`) can be set there.

## 6. Set up the app

**Settings → ComfyUI:**

| Setting | |
|---|---|
| ComfyUI address | Where ComfyUI answers. `http://127.0.0.1:8188` by default; `http://127.0.0.1:8000` for ComfyUI Desktop. |
| Workflow folder | The folder of section 5, relative to ComfyUI's `user/default` folder. |
| Default workflow for the image viewer | What the viewer starts with. *None* shows pages as stored. |
| Start ComfyUI when the app starts / Start script | See section 3. |

**Settings → ComfyUI status** shows whether ComfyUI answers, which version on which device, whether
the app started it, and its recent output (click *ComfyUI output* to open it). It also says whether
results reach the app without being written to ComfyUI's disk (section 8) and how much space the
processed pages take, with a button to clear them.

## 7. Reading with a workflow

In the viewer, open the **cogwheel** and pick a **Workflow** — or set a default in Settings. Then:

- the page is shown as stored at once, and replaced by the processed page when it is ready. A line in
  the top right corner says what ComfyUI is doing (*Queued*, *Waiting for ComfyUI to start*,
  *Upscale Image 45%, ~20 s left*). How long is left is judged from the last pages processed with the same
  workflow, as ComfyUI's own queue panel judges it, and scaled to the size of the page as far as the pages
  processed so far show that the size matters: an upscaler takes about four times as long on a page with four
  times the pixels, while a workflow that resizes every page first takes as long on any. The first page after
  the app starts, or after a workflow is exported again, only shows how far ComfyUI's current step has got;
- the next pages are prepared while you read — six by default, set under **Settings → Pages prepared ahead
  while reading**, with the previous page after the first four — one at a time, and only while the viewer is
  open. Turning or jumping to another page makes that page next, and a page ComfyUI is still working on for
  the page you left is stopped — pages you skipped are not processed for nothing. Going back to the chapter,
  to Settings or anywhere else, or picking *None*, stops it too. A page is left to finish while the viewer
  merely waits, though — a tab in the background or a phone put down still gets its page;
- press **S**, or the button next to Back, to see the page on screen as stored and compare; press it again,
  or turn the page, to see the processed pages again. Processing carries on meanwhile;
- if something goes wrong, the line says what, and the page is shown as stored.

Processed pages are PNG files and can be large — a 4x upscale is often tens of megabytes. If that is
slow on a phone, end the workflow with a node that scales the result down to the size you need.

## 8. Keeping disk writes down

Processing writes files: the page going into ComfyUI and the result coming out. On an SSD, many
large short-lived files add wear. Each can be kept off the SSD:

| What is written | Where | How to keep it off the SSD |
|---|---|---|
| The page sent to ComfyUI | ComfyUI's temp folder, **once per page**: the app names it after its content, so running a second workflow on the same page writes nothing new | Start ComfyUI with `--temp-directory` on a RAM disk |
| The result, inside ComfyUI | **Nothing**, when the *SaveImageWebsocket* node is installed: the result is sent to the app directly. Otherwise ComfyUI's temp folder | The node comes with the Windows portable build and a git install (`custom_nodes/websocket_image_save.py`). If Settings → ComfyUI status says it is missing, copy that file from [ComfyUI's repository](https://github.com/comfyanonymous/ComfyUI/tree/master/custom_nodes) into your `custom_nodes` folder and restart ComfyUI — or put the temp folder on a RAM disk |
| The result, in the app | **Settings → Temporary files → ComfyUI results cache**. On Linux a RAM disk (`/dev/shm`) is used automatically; elsewhere it is `data/.comfyui` | On Windows, enter a folder on a RAM disk there. The cache is pruned oldest-first past 2 GB (`app.comfyui.result-cache-max-mb`), and held to a quarter of an automatic RAM disk |
| The result, in your browser | Nothing: processed pages are sent with `Cache-Control: no-store`, so the browser keeps them in memory only | — |
| ComfyUI's database, `user/comfyui.db` | Created and migrated at every ComfyUI start | `--database-url sqlite:///:memory:` |
| ComfyUI's output folder | Never used: the app replaces the Save Image node and runs no other output node | — |
| Log files | ComfyUI writes none unless asked to (`--verbose LEVEL FILE`); the app keeps ComfyUI's output in memory | Leave it that way |
| Swap / page file | When memory runs out, the system swaps to the SSD — far more writes than everything above | Keep RAM disks modest in size, especially without a graphics card, where models load into the same memory |

A start script with all of it (Windows portable, RAM disk `R:`):

```bat
@echo off
.\python_embeded\python.exe -s ComfyUI\main.py --windows-standalone-build --disable-auto-launch --temp-directory R:\ --database-url sqlite:///:memory:
```

On Linux: `--temp-directory /dev/shm/comfyui --database-url sqlite:///:memory:`.

ComfyUI puts its temp files into a `temp` folder inside the one given (`R:\temp\`), and **empties it
only when it starts**, so the folder grows while ComfyUI runs: the pages you processed, plus the results
when SaveImageWebsocket is missing. 2–4 GB is plenty for a long session; restart ComfyUI (Settings →
ComfyUI status → Stop, then Start) if the RAM disk fills up.

**A RAM disk on Windows:** Windows has none built in. Free tools such as
[ImDisk Toolkit](https://sourceforge.net/projects/imdisk-toolkit/),
[AIM Toolkit](https://sourceforge.net/projects/aim-toolkit/) or OSFMount create one: choose a size and a
drive letter, and let it be created at Windows startup — a RAM disk is gone after a restart, and it must
exist before ComfyUI starts. Its contents use your computer's memory.

Also written, but rarely: ComfyUI's own settings and workflows when you use its browser interface, and
Python's `__pycache__` files once after installing or updating.

---

## Troubleshooting

| The viewer or Settings says… | What to do |
|---|---|
| **Cannot connect to ComfyUI … Is ComfyUI running?** | Start it (or tick autostart, section 3) and wait until its window or the Settings output shows `To see the GUI go to`. Check the address in Settings — ComfyUI Desktop uses port `8000`. |
| **Workflow '…' is not in ComfyUI's '…' folder** | The file is not in the workflow folder (section 5), or has another name, or does not end in `.json`. |
| **…saved in ComfyUI's regular format** | Export it again with **File → Export (API)**. |
| **…has several nodes that could be the input / output** | Rename the right Load Image node to `Input` and the right output node to `Output` (section 4), then export again. |
| **ComfyUI rejected workflow '…': … Value not in list: …** | The workflow uses a model file this ComfyUI does not have. Put it into the right `models` folder; the message lists the models ComfyUI can see. |
| **ComfyUI rejected workflow '…': … not found. The custom node may not be installed** | Install the custom node (for example with ComfyUI-Manager), then restart ComfyUI. |
| **ComfyUI failed to run workflow '…': … out of memory** | The page is too large for your graphics card. Close other GPU-heavy programs, scale the image down first in the workflow, or start ComfyUI with `--lowvram` or `--cpu`. |
| **ComfyUI failed to run workflow '…'** (anything else) | Run the workflow in ComfyUI's browser interface on the same page: it highlights the node that fails. Settings → ComfyUI status shows ComfyUI's full error if the app started it. |
| **…did not finish within 600 s, so it was cancelled** | Common without a graphics card or with heavy workflows. Use a lighter workflow, or raise `app.comfyui.job-timeout-seconds`. |
| **ComfyUI was started but does not answer at …** | The start script runs, but ComfyUI listens elsewhere — compare the address in Settings with the `To see the GUI go to` line in the output. |
| **ComfyUI exited with code …** | Read the last lines of output under Settings → ComfyUI status. A GPU/CUDA error usually means a graphics driver update, or `--cpu`. |
| **No space left on device** with a RAM disk | The RAM disk is full: stop and start ComfyUI to empty its temp folder, or make the RAM disk larger. |

## Security note

Keep ComfyUI listening on `127.0.0.1` — its default. ComfyUI has no password; the app is the only thing
that needs to reach it, and it puts its own login in front of every processed page. Starting ComfyUI with
`--listen 0.0.0.0`, or forwarding its port, lets anyone on the network run jobs on your computer.
