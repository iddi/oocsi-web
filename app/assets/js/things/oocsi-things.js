/*!
 * Fantastic Things - a collection of connected high-level design prototyping building blocks, using the OOCSI design middleware.
 * 
 * Copyright (c) 2024-2025 Mathias Funk, Industrial Design dept., Eindhoven University of Technology
 */
let globalSettings = {}

// set namespace for OOCSI communication and visualization

const getSrcOrigin = () => new URL(document.currentScript.src).origin;
const server = getSrcOrigin(); //"oocsi.id.tue.nl";
const namespace = "DCE2025";

// disable auto-init of P5
window.mocha = true;

window.addEventListener('DOMContentLoaded', () => {
  // add styles
  const style = document.head.appendChild(document.createElement('style'));
  style.sheet.insertRule(`* { -webkit-user-select: none; -ms-user-select: none; user-select: none; }`, 0);
  style.sheet.insertRule(`#header {
      position: fixed;
      width: 100%;
      display: block;
      padding: 0.1rem 0.5rem;
      background: rgba(0, 0, 0, 0.5);
      color: #fff9c1;
      cursor: pointer;
    }`, 1);
  style.sheet.insertRule(`#header span:hover { position: relative; top: -2px; }`, 2);
  style.sheet.insertRule(`#activity, #connection { opacity: 0; }`, 3);
  style.sheet.insertRule(`#header .grey { color: #777; }`, 4);
  style.sheet.insertRule(`#header .red { color: #f35588; }`, 5);
  style.sheet.insertRule(`#header .green { color: #78dec7; }`, 6);
  style.sheet.insertRule(`#header .purple { color: #c244fb; }`, 7);
  style.sheet.insertRule(`#header .blue { color: #84e9ff; }`, 8);


  let contents = document.querySelector('body').innerHTML;
  document.querySelector('body').innerHTML = `
    <div id="header"></div>
    <div id="canvasContainer"></div>` + contents;

  // if we have a teamspace configured, proceed with that
  if(window.fantasticThingsConfig && window.fantasticThingsConfig.teamspace) {
    finishSetup(window.fantasticThingsConfig.teamspace)
    return;
  }

  // otherwise, we select one via UI: create and open dialog
  document.querySelector('#canvasContainer').innerHTML = 
    `<dialog id="team-modal" open><article>
      <h2><a href="index.html" style="color: var(--pico-h2-color); text-decoration: none;">Fantastic 🌟</a></h2>
      <label for="team-choice">Choose a team:</label>
      <form>
        <fieldset role="group">
          <input list="team-choices" id="team-choice" name="team-choice" />
          <datalist id="team-choices">
            <option value="Team 1"></option>
            <option value="Team 2"></option>
            <option value="Team 3"></option>
            <option value="Team 4"></option>
            <option value="Team 5"></option>
            <option value="Team 6"></option>
            <option value="Team 7"></option>
            <option value="Team 8"></option>
            <option value="Team 9"></option>
            <option value="Team 10"></option>
            <option value="Team 11"></option>
            <option value="Team 12"></option>
            <option value="Team 13"></option>
            <option value="Team 14"></option>
            <option value="Team 15"></option>
            <option value="Team 16"></option>
            <option value="Team 17"></option>
            <option value="Team 18"></option>
            <option value="Team 19"></option>
            <option value="Team 20"></option>
            <option value="Team Test"></option>
          </datalist>
          <input type="submit" value="Go!">
        </fieldset>
      </form>
      <small>
        Connecting via <code>${server}</code>...
      </small>
    </article></dialog>`

  // set team saved in cookie
  let teamName = document.cookie.split("; ").find((row) => row.startsWith("ft_teamName="))?.split("=")[1] 
  document.querySelector('#team-choice').value = teamName !== undefined ? decodeURIComponent(teamName): '';

  // install button handler
  document.querySelector('#team-modal input[type=submit]').addEventListener('click', () => {
    let teamName = document.querySelector('#team-choice').value
    document.querySelector('#team-modal').remove();
    finishSetup(teamName);
  })

  function finishSetup(teamName) {
    // Convert to lowercase
    let str = teamName.toLowerCase();
    // Replace spaces with hyphens
    str = str.replace(/\s+/g, '-');
    // Remove non-alphanumeric except hyphen
    str = str.replace(/[^a-z0-9-]/g, '');
    // Trim leading/trailing hyphens
    let teamSlug = str.replace(/^-+|-+$/g, '');
    globalSettings.team = teamName;
    globalSettings.channel = (namespace && namespace.length > 0 ? (namespace + '/') : '') + teamSlug;
    setCookie('ft_teamName', teamName)

    window.thing && window.thing();

    // delete auto-init flag
    delete window.mocha;
    // init P5
    window.p5 !== undefined && new p5();
  }

  function setCookie(name, value) {
    const expires = new Date(Date.now() + 365 * 24 * 60 * 60 * 1000).toUTCString();
    document.cookie = `${name}=${value}; expires=${expires}; path=/`;
  }
})

window.fantasticThings = (() => {

  let data = {};
  let devices = {};
  let deviceMotionSupported;

  return {
    register: (name, slug, inVars, outVars) => {

      // set title
      document.title = 'Fantastic Things: ' + name;

      // choose and connect to the right OOCSI server
      OOCSI.connect('wss://' + server + '/ws', globalSettings.channel + '/' + slug + '_##'); 
      //OOCSI.connect('ws://localhost:9000/ws');

      // app settings
      globalSettings.appName = name;
      globalSettings.appSlug = slug;
      globalSettings.id = globalSettings.appSlug + Date.now() % 1000;

      // start sending the presence updates
      setInterval(() => {
        OOCSI.send(globalSettings.channel,
          {
            name: globalSettings.appName, 
            slug: globalSettings.appSlug,
            id: globalSettings.id,
            in: inVars, 
            out: outVars
          })
      }, 8000)
      OOCSI.subscribe(globalSettings.channel, (e) => {
        if(e.data.id !== undefined) {
          devices[e.data.id] = e.data
          devices[e.data.id].timestamp = Date.now()
        }
      })

      // update header
      let header = document.querySelector('#header');
      header.innerHTML = `<b class="grey">${globalSettings.team}:</b> ${globalSettings.appName} `;
      for (let inV of inVars) {
        header.innerHTML += `<span title="${inV}" class="purple">&darr;</span>`;
      }
      for (let outV of outVars) {
        header.innerHTML += `<span title="${outV}" class="blue">&uarr;</span>`;
      }
      header.innerHTML += `<span id="activity">&starf;</span>`
      header.innerHTML += `<span id="connection" class="red">&#9832;</span>`
      header.addEventListener('click', () => {
        window.location.reload();
      })
      // remove extra stuff
      // document.querySelector('main').style.display = 'none';
      document.querySelector('body').style.overflow = 'hidden';

      setInterval(() => {
        if(!OOCSI.isConnected()) {
          document.querySelector('#connection').style.opacity = '1.0';
        } else {
          document.querySelector('#connection').style.opacity = '0';
        }
      }, 2000)
    },
    data: (name) => {
      data[name] = OOCSI.variable(globalSettings.channel, globalSettings.appSlug + '_' + name);
      return data[name]
    },
    connect: (app, name, ownData) => {
      if(data[ownData]) {
        // connect data at app_name to ownData
        let app_name = OOCSI.variable(globalSettings.channel, app + '_' + name)
        app_name.subscribe((value) => {
          data[ownData](value);
        });
      }
    },
    activity: () => {
      document.querySelector('#activity').style.opacity = '1.0';
      setTimeout(() => {
        document.querySelector('#activity').style.opacity = '0';
      }, 200)
    },
    devices: () => {
      // clean devices first
      const currentTime = Date.now();
      devices = Object.entries(devices).reduce((acc, [key, value]) => {
        const timestampMs = value.timestamp;
        const timestampDiff = currentTime - timestampMs;
        if (timestampDiff <= 20 * 1000) {
          // Keep the key-value pair if it's within the allowed range
          acc[key] = value;
        }
        return acc;
      }, {});
      return devices;
    },
    requestDeviceMotion: () => {
      if(deviceMotionSupported !== undefined) {
        return deviceMotionSupported;
      }
      
      if (typeof DeviceMotionEvent !== 'undefined' && typeof DeviceMotionEvent.requestPermission === 'function') {

        // try a first attempt to request motion sensor access
        DeviceMotionEvent.requestPermission()
          .then(response => {
              if (response === 'granted') {
                // Permission granted, proceed with event listener
                deviceMotionSupported = true;
              }
          })
          .catch((e) => {
            // otherwise, insert div with button to request the device motion
            const newDiv = `
              <div id="requestMotionElement" style="position: fixed; top: 0; left: 0; width: 100vw; height: 100vh; background-color: rgba(0, 0, 0, 0.8); display: flex; justify-content: center; align-items: center; z-index: 1000;">
                <button>Request device motion</button>
              </div>
            `;

            // Insert the new div after the header
            const header = document.querySelector('#header');
            header.insertAdjacentHTML('afterend', newDiv);

            // Add click event to the button to remove the new div
            const button = document.querySelector('#requestMotionElement button');
            button.onclick = function () {
              DeviceMotionEvent.requestPermission()
                .then(response => {
                    if (response === 'granted') {
                      // Permission granted, proceed with event listener
                      deviceMotionSupported = true;
                    }
                    document.querySelector('#requestMotionElement').remove();
                })
                .catch((e) => {
                  deviceMotionSupported = false;
                  console.error(e);
                });
            };
          });
      } else {
        deviceMotionSupported = false;
        console.error('DeviceMotionEvent not supported');
      }

      return deviceMotionSupported;
    }
  };
})();