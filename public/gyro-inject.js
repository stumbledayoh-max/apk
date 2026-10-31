/* ==================================================================
 *  gyro-inject.js — ponte do "giroscópio emulado por acelerômetro"
 *  Este arquivo é carregado DENTRO de cada jogo (injetado no <head>),
 *  antes de qualquer script do jogo. Ele:
 *
 *   1. intercepta os listeners de deviceorientation/devicemotion para
 *      que o jogo receba ONLY as amostras sintéticas do launcher;
 *   2. recebe as amostras por postMessage e dispara eventos sintéticos
 *      idênticos aos de um aparelho real (alpha/beta/gamma, rotationRate,
 *      accelerationIncludingGravity);
 *   3. expõe window.FakeGyro para o launcher (recentralizar, 6DoF...);
 *   4. no CraftVR, injeta o 6DoF posicional (andar de verdade) direto
 *      no objeto xr6 do jogo;
 *   5. cria um stub de Hands quando o MediaPipe não está disponível.
 * ================================================================== */
(function () {
  "use strict";

  var VERSION = "1.0";
  var SENSOR = ["deviceorientation", "deviceorientationabsolute", "devicemotion"];

  var cfg = {
    streaming: true,
    autoEnable: true,
    camera: true,
    positional: false,
    sensitivity: 1,
    smoothing: 0.35,
  };

  var registered = []; // {type, fn, opts}
  var origAdd = window.addEventListener;
  var origRemove = window.removeEventListener;
  var propHandlers = {};

  /* ---------- intercepta addEventListener para os eventos de sensor ---------- */
  window.addEventListener = function (type, fn, opts) {
    if (SENSOR.indexOf(type) !== -1 && typeof fn === "function") {
      registered.push({ type: type, fn: fn, opts: opts });
      return undefined;
    }
    return origAdd.apply(this, arguments);
  };
  window.removeEventListener = function (type, fn, opts) {
    if (SENSOR.indexOf(type) !== -1) {
      for (var i = registered.length - 1; i >= 0; i--) {
        if (registered[i].type === type && registered[i].fn === fn) registered.splice(i, 1);
      }
      return undefined;
    }
    return origRemove.apply(this, arguments);
  };

  // window.ondeviceorientation = fn  (alguns jogos usam a propriedade)
  try {
    Object.defineProperty(window, "ondeviceorientation", {
      configurable: true,
      get: function () {
        return propHandlers.deviceorientation || null;
      },
      set: function (fn) {
        propHandlers.deviceorientation = typeof fn === "function" ? fn : null;
      },
    });
    Object.defineProperty(window, "ondevicemotion", {
      configurable: true,
      get: function () {
        return propHandlers.devicemotion || null;
      },
      set: function (fn) {
        propHandlers.devicemotion = typeof fn === "function" ? fn : null;
      },
    });
  } catch (e) {
    /* segue */
  }

  function deliver(type, ev) {
    var i, rec;
    for (i = 0; i < registered.length; i++) {
      rec = registered[i];
      if (rec.type !== type) continue;
      try {
        rec.fn.call(window, ev);
      } catch (e) {
        /* ignora erro de listener do jogo */
      }
    }
    var ph = propHandlers[type];
    if (ph) {
      try {
        ph.call(window, ev);
      } catch (e2) {
        /* ignora */
      }
    }
  }

  /* ------------------------- eventos sintéticos ------------------------- */

  function makeOrient(a, b, g, absolute) {
    var ev = null;
    try {
      ev = new DeviceOrientationEvent("deviceorientation", {
        alpha: a,
        beta: b,
        gamma: g,
        absolute: !!absolute,
      });
    } catch (e) {
      ev = document.createEvent("Event");
      ev.initEvent("deviceorientation", false, false);
      ev.alpha = a;
      ev.beta = b;
      ev.gamma = g;
      ev.absolute = !!absolute;
    }
    return ev;
  }

  function makeMotion(m) {
    var ev = null;
    var acc = { x: m.ax, y: m.ay, z: m.az };
    var rot = { alpha: m.rx, beta: m.ry, gamma: m.rz };
    try {
      ev = new DeviceMotionEvent("devicemotion", {
        accelerationIncludingGravity: acc,
        acceleration: acc,
        rotationRate: rot,
        interval: m.interval,
      });
    } catch (e) {
      ev = document.createEvent("Event");
      ev.initEvent("devicemotion", false, false);
      ev.accelerationIncludingGravity = acc;
      ev.acceleration = acc;
      ev.rotationRate = rot;
      ev.interval = m.interval;
    }
    return ev;
  }

  /* --------------------------- 6DoF posicional --------------------------- */

  var lastPos = { dx: 0, dz: 0, dy: 0, crouch: false };
  var xr6ok = false;

  function posLoop() {
    window.requestAnimationFrame(posLoop);
    if (!cfg.positional) return;
    try {
      if (typeof xr6 !== "undefined" && xr6 && !xr6.ses) {
        if (!xr6ok) {
          xr6ok = true;
          announce(); // avisa o launcher que o 6DoF posicional está disponível
        }
        xr6.on = true;
        if (lastPos.dx) xr6.dx += lastPos.dx;
        if (lastPos.dz) xr6.dz += lastPos.dz;
        xr6.crouch = lastPos.crouch;
      }
    } catch (e) {
      /* o jogo pode não ter xr6 */
    }
  }
  window.requestAnimationFrame(posLoop);

  /* ------------------------------- API ------------------------------- */

  function enableGameGyro() {
    var fns = ["enableGyro", "startGyro", "toggleGyro", "initGyro"];
    for (var i = 0; i < fns.length; i++) {
      var f = window[fns[i]];
      if (typeof f === "function") {
        try {
          f.call(window);
          return true;
        } catch (e) {
          /* tenta o próximo */
        }
      }
    }
    return false;
  }

  var api = {
    version: VERSION,
    streaming: true,
    setStreaming: function (on) {
      cfg.streaming = !!on;
      api.streaming = cfg.streaming;
    },
    setConfig: function (patch) {
      if (!patch) return;
      var k;
      for (k in patch) {
        if (Object.prototype.hasOwnProperty.call(patch, k)) cfg[k] = patch[k];
      }
    },
    recenter: function () {
      // o launcher cuida do offset de guinada; só repassamos
    },
    reset: function () {
      lastPos.dx = lastPos.dz = lastPos.dy = 0;
    },
    enableGameGyro: enableGameGyro,
    setPositional: function (on) {
      cfg.positional = !!on;
      if (!cfg.positional && xr6ok) {
        try {
          if (typeof xr6 !== "undefined" && xr6) xr6.on = false;
        } catch (e) {
          /* segue */
        }
      }
      return xr6ok;
    },
    hasPositionalApi: function () {
      return xr6ok;
    },
  };
  window.FakeGyro = api;

  /* --------------------- stub do MediaPipe (offline) --------------------- */
  if (typeof window.Hands === "undefined") {
    window.Hands = function Hands() {
      this._cb = null;
    };
    window.Hands.prototype.setOptions = function () {};
    window.Hands.prototype.onResults = function (cb) {
      this._cb = cb;
    };
    window.Hands.prototype.send = function () {
      return Promise.resolve();
    };
    window.Hands.prototype.close = function () {};
    window.Hands.prototype.initialize = function () {
      return Promise.resolve();
    };
  }

  /* --------------------- mensagens do launcher --------------------- */

  var autoEnabled = false;

  /**
   * Liga o giroscópio do próprio jogo somente quando os dados começam a
   * chegar — vários jogos desativam o giro se ficarem ~2s sem amostras.
   */
  function maybeAutoEnable() {
    if (autoEnabled || !cfg.autoEnable) return;
    autoEnabled = true;
    enableGameGyro();
  }

  window.addEventListener(
    "message",
    function (e) {
      var d = e.data;
      if (!d || typeof d !== "object") return;
      switch (d.type) {
        case "fg:orient":
          if (!cfg.streaming) return;
          maybeAutoEnable();
          deliver("deviceorientation", makeOrient(d.alpha, d.beta, d.gamma, d.absolute));
          if (d.absolute) {
            deliver("deviceorientationabsolute", makeOrient(d.alpha, d.beta, d.gamma, true));
          }
          break;
        case "fg:motion":
          if (!cfg.streaming) return;
          deliver("devicemotion", makeMotion(d));
          break;
        case "fg:pos6":
          lastPos.dx = d.dx;
          lastPos.dz = d.dz;
          lastPos.dy = d.dy;
          lastPos.crouch = d.crouch;
          break;
        case "fg:config":
          api.setConfig(d.config);
          if (d.streaming !== undefined) api.setStreaming(d.streaming);
          applyCameraSetting();
          break;
        case "fg:recenter":
          api.recenter();
          break;
        case "fg:reset":
          api.reset();
          break;
        case "fg:ping":
          reply({ type: "fg:pong", t: d.t || 0 });
          break;
        default:
          break;
      }
    },
    false,
  );

  function reply(obj) {
    try {
      if (window.parent && window.parent !== window) window.parent.postMessage(obj, "*");
    } catch (e) {
      /* segue */
    }
  }

  /* ------------------------- câmera opcional ------------------------- */
  function applyCameraSetting() {
    if (cfg.camera) return;
    try {
      if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
        navigator.mediaDevices.getUserMedia = function () {
          return Promise.reject(new Error("câmera desativada nas configurações do launcher"));
        };
      }
      if (typeof window.alert === "function") {
        window.alert = function () {};
      }
    } catch (e) {
      /* segue */
    }
  }
  applyCameraSetting();

  /* --------------------------- anúncio --------------------------- */
  function announce() {
    var game = (document.title || "jogo").trim() || "jogo";
    reply({
      type: "fg:ready",
      version: VERSION,
      game: game,
      gyroApi: typeof window.enableGyro === "function" || !!window.FakeGyro,
      positionalApi: xr6ok,
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", announce);
  } else {
    announce();
  }
})();
