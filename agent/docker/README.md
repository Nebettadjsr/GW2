# Agent workflow containers

This directory contains Docker tooling for the AI development workflow. It is not part of the GW2 application's deployment.

- [Grafana](grafana/README.md): read-only workflow visualization, pinned image and automatic dashboard provisioning.

The Python workflow service and model CLIs continue running on the host; starting these containers does not start agents or change story state.
