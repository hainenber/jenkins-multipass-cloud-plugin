Behaviour.specify(
  "[data-type='multipass-vm-provision']",
  "multipass",
  -99,
  (e) => {
    e.addEventListener("click", (event) => {
      const form = document.getElementById(e.dataset.form);
      buildFormTree(form);
      form.submit();
    });
  },
);
