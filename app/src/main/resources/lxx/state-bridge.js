(function (root) {
  'use strict';
  if (root.__musicEnhanceLxxObserver) return;
  root.__musicEnhanceLxxObserver = true;
  var attached = null;
  var listener = null;
  var configListener = null;
  var settings = null;
  var common = null;
  var controlsBridge = null;
  var modeKey = 'player.togglePlayMethod';
  // 26.09.20 MUSIC_TOGGLE_MODE_LIST; heartbeat requires a separate online recommendation flow.
  var modes = ['listLoop', 'random', 'list', 'singleLoop', 'none'];
  function sendNative(marker, info) {
    try {
      var payload = JSON.stringify(info);
      var nativeLyric = root.nativeModuleProxy && root.nativeModuleProxy.LyricModule;
      if (nativeLyric && typeof nativeLyric.setLyric === 'function') {
        var result = nativeLyric.setLyric(marker, payload, '', '');
        if (result && typeof result.catch === 'function') result.catch(function () {});
        return;
      }
      var bridge = root.__fbBatchedBridge;
      var configs = root.__fbBatchedBridgeConfig && root.__fbBatchedBridgeConfig.remoteModuleConfig;
      if (!bridge || !configs) return;
      var index = configs.findIndex(function (entry) { return entry && entry[0] === 'LyricModule'; });
      if (index < 0) return;
      var config = configs[index];
      if (!config[2] && root.nativeRequireModuleConfig) config = root.nativeRequireModuleConfig('LyricModule');
      if (typeof config === 'string') config = JSON.parse(config);
      var method = config && config[2] && config[2].indexOf('setLyric');
      if (typeof method !== 'number' || method < 0) return;
      // Reuse an existing RN transport. The adapter consumes only this exact marker; normal
      // desktop lyrics continue through the original method with their original arguments.
      bridge.enqueueNativeCall(index, method, [marker, payload, '', ''], function () {}, function () {});
    } catch (_) { /* Host event dispatch must never be interrupted by the optional observer. */ }
  }
  function sendSong(info) {
    info = info || {};
    sendNative('MusicEnhance.Lxx.State.v1', { id: info.id, name: info.name, singer: info.singer,
      album: info.album, pic: info.pic, lrc: info.lrc });
    publishControls();
  }
  function publishControls() {
    if (!settings || !common) return;
    try {
      var bridge = root.__fbBatchedBridge;
      if (!bridge || typeof bridge.registerCallableModule !== 'function') return;
      if (controlsBridge !== bridge) {
        bridge.registerCallableModule('MusicEnhanceLxxControls', {
          cycleRepeat: function () {
            try {
              var index = modes.indexOf(settings.setting[modeKey]);
              if (index < 0) return; // Do not overwrite an unsupported host mode.
              var update = {};
              update[modeKey] = modes[(index + 1) % modes.length];
              common.updateSetting(update); // Includes native UI notification and throttled persistence.
              publishControls();
            } catch (_) {
              sendNative('MusicEnhance.Lxx.Controls.v1', { ready: false });
            }
          }
        });
        controlsBridge = bridge;
      }
      var mode = settings.setting[modeKey];
      sendNative('MusicEnhance.Lxx.Controls.v1', { mode: mode, ready: modes.indexOf(mode) >= 0 });
    } catch (_) { /* Optional controls must not interrupt module initialization. */ }
  }
  // Metro's release bundle has numeric module IDs. Observe only exports of factories the host
  // itself executes, instead of requiring arbitrary IDs or initializing unused modules.
  function dataValue(object, key) {
    if (!object || (typeof object !== 'object' && typeof object !== 'function')) return undefined;
    var descriptor = Object.getOwnPropertyDescriptor(object, key);
    return descriptor && descriptor.value; // Never execute lazy export getters for discovery.
  }
  function observeExports(exports) {
    if (!exports || (typeof exports !== 'object' && typeof exports !== 'function')) return;
    var value = dataValue(exports, 'default');
    var setting = dataValue(value, 'setting');
    if (!settings && setting && Object.prototype.hasOwnProperty.call(setting, modeKey)) settings = value;
    if (!common && typeof dataValue(exports, 'updateSetting') === 'function' && typeof dataValue(exports, 'setNavActiveId') === 'function' &&
        typeof dataValue(exports, 'initSetting') === 'function' && typeof dataValue(exports, 'exitApp') === 'function') common = exports;
    if (settings && common) publishControls();
  }
  function wrapDefine(define) {
    if (typeof define !== 'function') return define;
    return function () {
      var args = Array.prototype.slice.call(arguments);
      var factory = args[0];
      if (typeof factory === 'function') args[0] = function () {
        var result = factory.apply(this, arguments);
        if (!settings || !common) {
          try { observeExports(arguments[4] && arguments[4].exports); } catch (_) {}
        }
        return result;
      };
      return define.apply(this, args);
    };
  }
  var defineDescriptor = Object.getOwnPropertyDescriptor(root, '__d');
  if (!defineDescriptor || (defineDescriptor.configurable && !defineDescriptor.get && !defineDescriptor.set)) {
    var define = wrapDefine(root.__d);
    Object.defineProperty(root, '__d', {
      configurable: true, enumerable: defineDescriptor ? defineDescriptor.enumerable : true,
      get: function () { return define; },
      set: function (value) { define = wrapDefine(value); }
    });
  }
  function attach(events) {
    try {
      if (attached === events) return;
      if (attached && listener && typeof attached.off === 'function') attached.off('playerMusicInfoChanged', listener);
      if (attached && configListener && typeof attached.off === 'function') attached.off('configUpdated', configListener);
      attached = events;
      listener = null;
      configListener = null;
      if (events && typeof events.on === 'function') {
        listener = function (info) { sendSong(info); };
        configListener = function (keys) { if (keys && keys.indexOf(modeKey) >= 0) publishControls(); };
        events.on('playerMusicInfoChanged', listener);
        events.on('configUpdated', configListener);
      }
    } catch (_) { /* An unsupported event bus must not break the host's initialization. */ }
  }
  var descriptor = Object.getOwnPropertyDescriptor(root, 'state_event');
  if (!descriptor || (descriptor.configurable && !descriptor.get && !descriptor.set)) {
    var current = root.state_event;
    Object.defineProperty(root, 'state_event', {
      configurable: true, enumerable: descriptor ? descriptor.enumerable : true,
      get: function () { return current; },
      set: function (value) { current = value; attach(value); }
    });
    attach(current);
  }
})(typeof globalThis === 'object' ? globalThis : this);
