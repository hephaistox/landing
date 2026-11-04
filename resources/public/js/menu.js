// Marks the current page in the left menu: its link gets aria-current="page".
(function () {
  var here = location.pathname;
  document.querySelectorAll('#menu-sidebar a[href]').forEach(function (a) {
    if (new URL(a.getAttribute('href'), location.href).pathname === here) {
      a.setAttribute('aria-current', 'page');
    }
  });
})();
