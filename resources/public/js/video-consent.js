// Loads a third-party video only when the visitor asks for it: until the click, the page
// contacts no video provider. Each placeholder carries the iframe source in data-src.
(function () {
  document.querySelectorAll('[data-video-consent]').forEach(function (box) {
    box.querySelector('button').addEventListener('click', function () {
      var iframe = document.createElement('iframe');
      iframe.src = box.getAttribute('data-src') + (box.getAttribute('data-src').indexOf('?') < 0 ? '?' : '&') + 'autoplay=1';
      iframe.title = box.getAttribute('data-title');
      iframe.allow = 'accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share';
      iframe.allowFullscreen = true;
      iframe.referrerPolicy = 'strict-origin-when-cross-origin';
      iframe.className = 'w3-border-0';
      iframe.style.cssText = 'height:100%;left:0;position:absolute;top:0;width:100%;';
      box.replaceChildren(iframe);
    });
  });
})();
