Behaviour.specify(
  "[data-type='multipass-vm-provision']",
  "multipass-vm-provision",
  -99,
  (e) => {
    e.addEventListener("click", (event) => {
      const form = document.getElementById(e.dataset.form);
      form.querySelector("[name='template']").value = e.dataset.url;
      buildFormTree(form);
      form.submit();
    });
  },
);
