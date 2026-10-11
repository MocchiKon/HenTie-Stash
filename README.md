# HenTie


HenTie is a local web app for browsing and managing your collection, with simple installation. 
Other devices on your home network (phone, tablet, another PC) can access it too via browser.

---

## 1. Running it

There are two downloads:

| Download | For | Java needed? |
|----------|-----|--------------|
| **`HenTie-windows.zip`** | Windows | **No** — Java comes with it (the `jre` folder) |
| **`HenTie-all-platforms.zip`** | Windows, Linux, macOS | **Yes, Java 21 (or newer)** |

If you need Java, download the free "Temurin 21" installer from <https://adoptium.net/> and run it.

Unzip the download and keep everything in the folder together (`HenTie.exe` needs its `jre` folder
next to it, and both need `bin`). Then, in that folder:

| You are on… | Do this |
|-------------|---------|
| **Windows** | Double-click **`HenTie.exe`** (or **`start.bat`**) |
| Anything else | Run **`start.sh`**, double-click **`HenTie.jar`**, or run `java -jar HenTie.jar` in a terminal |

Console window will appear — **leave it open** while you use the app (closing it stops the app).
Your browser should open automatically at <http://localhost:7395>. If it doesn't, open that
address yourself.

Logging in, opening the app from your phone and setting up ComfyUI are explained on the **Guide** page,
linked at the top of every page of the app.

---

## 2. Building it yourself (optional, for developers)

```
./mvnw clean package
```

This produces `target/HenTie.jar` (runs anywhere with Java) and, on Windows, `target/HenTie.exe` with
a bundled Java runtime in `target/jre` (the exe needs it beside it, or an installed Java 21+).
`./mvnw package -PpackageForRelease` also builds the release zips.
