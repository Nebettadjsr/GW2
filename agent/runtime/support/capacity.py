"""Capacity signals shared by the model runners."""


class ModelCapacityUnavailable(RuntimeError):
    """Usage exhaustion is a scheduling event, not a failed work result."""
