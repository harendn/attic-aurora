import copy

from twitter.common import log
from twitter.common.recordio import ThriftRecordReader
from gen.twitter.thermos.ttypes import *

class TaskCkptDispatcher(object):
  """
    The reconstruction / dispatching mechanism for logic triggered on
    task/process state transitions.

    Most applications should build an event-loop around the
    TaskCkptDispatcher.
  """

  class Error(Exception):
    pass

  class ErrorRecoveringState(Error): pass
  class InvalidStateTransition(Error): pass
  class InvalidSequenceNumber(Error): pass

  @staticmethod
  def from_file(filename):
    state = TaskRunnerState(processes = {})
    builder = TaskCkptDispatcher()
    with open(filename, 'r') as fp:
      rr = ThriftRecordReader(fp, TaskRunnerCkpt)
      try:
        for process_update in rr:
          builder.update_runner_state(state, process_update)
        return state
      except TaskCkptDispatcher.Error:
        return None

  def __init__(self):
    self._state_handlers = {}
    self._universal_handlers = []
    self._port_handlers = []

  def register_state_handler(self, run_state, function):
    """
      Register a function callback for when a process transitions its run state.
      Current run states: WAITING, FORKED, RUNNING, FINISHED, KILLED, FAILED, LOST.
    """
    if run_state not in self._state_handlers:
      self._state_handlers[run_state] = []
    self._state_handlers[run_state].append(function)

  def register_port_handler(self, function):
    """
       Register a function callback to handle when a port is allocated by the runner.
    """
    self._port_handlers.append(function)

  def register_universal_handler(self, function):
    """
      Register a function callback that gets called on every process state transition.
    """
    self._universal_handlers.append(function)

  def _run_state_dispatch(self, state, process_update):
    for handler in self._universal_handlers:
      handler(process_update)
    for handler in self._state_handlers.get(state, []):
      handler(process_update)

  def _run_port_dispatch(self, name, port):
    for handler in self._port_handlers:
      handler(name, port)

  @staticmethod
  def check_empty_fields(process_state, fields):
    for field in fields:
      if process_state.__dict__[field] is not None:
        raise TaskCkptDispatcher.ErrorRecoveringState(
          "Field field %s from %s should be empty, instead got: %s" % (
            field, process_state, process_state.__dict__[field]))

  @staticmethod
  def check_nonempty_fields(process_state, fields):
    for field in fields:
      if process_state.__dict__[field] is None:
        raise TaskCkptDispatcher.ErrorRecoveringState(
          "Missing field %s from %s!" % (field, process_state))

  @staticmethod
  def check_and_copy_fields(process_state, process_state_update, fields):
    TaskCkptDispatcher.check_empty_fields(process_state, fields)
    TaskCkptDispatcher.check_nonempty_fields(process_state_update, fields)
    for field in fields:
      process_state.__dict__[field] = copy.deepcopy(process_state_update.__dict__[field])

  @staticmethod
  def copy_fields(process_state, process_state_update, fields):
    TaskCkptDispatcher.check_nonempty_fields(process_state_update, fields)
    for field in fields:
      process_state.__dict__[field] = copy.deepcopy(process_state_update.__dict__[field])

  @staticmethod
  def is_terminal(process_state_update):
    TERMINAL_STATES = [
      ProcessRunState.FINISHED,
      ProcessRunState.FAILED,
      ProcessRunState.KILLED,
      ProcessRunState.LOST]
    return process_state_update.run_state in TERMINAL_STATES

  def update_task_state(self, process_state, process_state_update, recovery):
    """
      Apply process_state_update against process_state.

      set recovery = True if you are in checkpoint recovery mode (i.e. you expect
        to see replays of ckpts from forked children.)

      returns True if a state update was applied to process_state
    """
    if process_state_update.seq is None:
      raise TaskCkptDispatcher.InvalidSequenceNumber(
        "Got nil suquence number! update = %s" % process_state_update)

    # Special-casing seq == 0 is kind of blech.  Should we create an INIT state?
    if process_state.seq > 0:
      if process_state_update.seq <= process_state.seq:
        if recovery:
          # in recovery mode, we expect to see out of order updates from
          # process checkpoints since we are starting over at sequence number
          # 0.  if not in recovery mode, this is an error.
          return False
        else:
          raise TaskCkptDispatcher.InvalidSequenceNumber(
            "Out of order sequence number! %s => %s" % (
              process_state, process_state_update))

      # We should not see non-contiguous sequence numbers, but keep it at a
      # warning for now until we're certain there are no bugs.
      if process_state_update.seq != process_state.seq + 1:
        log.error("WARNING: Noncontiguous sequence number: %s => %s" % (
          process_state, process_state_update))

    if process_state_update.run_state is not None:
      if process_state.run_state == process_state_update.run_state:
        raise TaskCkptDispatcher.InvalidStateTransition(
          "Must transition between states: Got %s=>%s, %s vs %s" % (
            process_state.run_state, process_state_update.run_state,
            process_state, process_state_update))

    # see thrift/thermos_runner.thrift for more explanation of the state transitions
    if process_state_update.run_state is not None:
      # [CREATION] => WAITING
      if process_state_update.run_state == ProcessRunState.WAITING:
        required_fields = ['seq', 'run_state', 'process']
        TaskCkptDispatcher.copy_fields(process_state, process_state_update, required_fields)

      # WAITING => FORKED
      elif process_state_update.run_state == ProcessRunState.FORKED:
        if process_state.run_state != ProcessRunState.WAITING:
          raise TaskCkptDispatcher.InvalidStateTransition(
            "%s => %s" % (process_state, process_state_update))
        required_fields = ['seq', 'run_state', 'fork_time', 'runner_pid']
        TaskCkptDispatcher.copy_fields(process_state, process_state_update, required_fields)

      # FORKED => RUNNING
      elif process_state_update.run_state == ProcessRunState.RUNNING:
        if process_state.run_state != ProcessRunState.FORKED:
          raise TaskCkptDispatcher.InvalidStateTransition(
            "%s => %s" % (process_state, process_state_update))
        required_fields = ['seq', 'run_state', 'start_time', 'pid']
        TaskCkptDispatcher.copy_fields(process_state, process_state_update, required_fields)

      # RUNNING => FINISHED
      elif process_state_update.run_state == ProcessRunState.FINISHED:
        if process_state.run_state != ProcessRunState.RUNNING:
          raise TaskCkptDispatcher.InvalidStateTransition(
            "%s => %s" % (process_state, process_state_update))
        required_fields = ['seq', 'run_state', 'stop_time', 'return_code']
        TaskCkptDispatcher.copy_fields(process_state, process_state_update, required_fields)

      # RUNNING => FAILED
      elif process_state_update.run_state == ProcessRunState.FAILED:
        if process_state.run_state != ProcessRunState.RUNNING:
          raise TaskCkptDispatcher.InvalidStateTransition(
            "%s => %s" % (process_state, process_state_update))
        required_fields = ['seq', 'run_state', 'stop_time', 'return_code']
        TaskCkptDispatcher.copy_fields(process_state, process_state_update, required_fields)

      # {FORKED, RUNNING} => KILLED
      elif process_state_update.run_state == ProcessRunState.KILLED:
        if process_state.run_state not in (ProcessRunState.FORKED, ProcessRunState.RUNNING):
          raise TaskCkptDispatcher.InvalidStateTransition(
            "%s => %s" % (process_state, process_state_update))
        required_fields = ['seq', 'run_state', 'stop_time']
        TaskCkptDispatcher.copy_fields(process_state, process_state_update, required_fields)

      # {FORKED, RUNNING} => LOST
      elif process_state_update.run_state == ProcessRunState.LOST:
        if process_state.run_state not in (ProcessRunState.FORKED, ProcessRunState.RUNNING):
          raise TaskCkptDispatcher.InvalidStateTransition(
            "%s => %s" % (process_state, process_state_update))
        required_fields = ['seq', 'run_state']
        TaskCkptDispatcher.copy_fields(process_state, process_state_update, required_fields)
      else:
        raise TaskCkptDispatcher.ErrorRecoveringState(
          "Unknown run_state = %s" % process_state_update.run_state)

    # dispatch state change to consumer
    self._run_state_dispatch(process_state_update.run_state, process_state_update)
    return True

  def would_update(self, state, runner_ckpt):
    """
      Provided a ProcessState, would this perform a transition and update state?
    """
    process_update = runner_ckpt.process_state
    if process_update is None:
      return False

    process = process_update.process
    if process not in state.processes: # never seen before
      return True
    else:
      # We have seen this process, so the state update must pertain to the current run.
      task_process = state.processes[process]
      task_state = task_process.runs[-1]
      # if this sequence number is ahead of the current high water mark, it would
      # produce a transition
      return task_state.seq < process_update.seq

  def update_runner_state(self, state, runner_ckpt, recovery = False):
    """
      state          = TaskRunnerState to apply process update
      process_update = TaskRunnerCkpt update
      recovery       => Pass in as true if you are in recovery mode

      returns True if process_update was applied to state.
    """
    # case 1: runner_header
    #   -> Initialization of the task stream.
    if runner_ckpt.runner_header is not None:
      if state.header is not None:
        raise TaskCkptDispatcher.ErrorRecoveringState(
          "Attempting to rebind task with different parameters!")
      else:
        log.debug('Initializing TaskRunner header to %s' % runner_ckpt.runner_header)
        state.header = runner_ckpt.runner_header
        return True

    # case 2: allocated_port
    #   -> Allocated a named ephemeral port to a process
    if runner_ckpt.allocated_port is not None:
      port, port_name = runner_ckpt.allocated_port.port, runner_ckpt.allocated_port.port_name
      if state.ports is None:
        state.ports = {}
      if port_name in state.ports:
        if port != state.ports[port_name]:
          raise TaskCkptDispatcher.ErrorRecoveringState(
            "Port assignment conflicts with earlier assignment: %s" % port_name)
        else:
          return False
      else:
        log.debug('Assigning named port %s to %s' % (port_name, port))
        state.ports[port_name] = port
        self._run_port_dispatch(port_name, port)
        return True

    # case 3: state_update
    #   -> State transition on the task (ACTIVE, FAILED, FINISHED)
    if runner_ckpt.state_update is not None:
      if state.state != runner_ckpt.state_update.state:
        old_state = state.state
        state.state = runner_ckpt.state_update.state
        log.debug('Flipping task state from %s to %s' % (
          TaskState._VALUES_TO_NAMES[old_state] if old_state is not None else '(undefined)',
          TaskState._VALUES_TO_NAMES[state.state]))
        return True
      return False

    # case 4: history_state_update
    #   -> State transition on the run of a process within the task (ACTIVE, FAILED, FINISHED)
    if runner_ckpt.history_state_update is not None:
      process_name = runner_ckpt.history_state_update.process
      state_change = runner_ckpt.history_state_update.state
      if state.processes[process_name].state != state_change:
        old_state = state.processes[process_name].state
        state.processes[process_name].state = state_change
        log.debug('Flipping process %s history state from %s to %s' % (
          process_name,
          TaskRunState._VALUES_TO_NAMES[old_state] if old_state is not None else '(undefined)',
          TaskRunState._VALUES_TO_NAMES[state_change]))
        return True
      return False

    # case 5: process_state
    #   -> State transition on a process itself
    #        (WAITING, FORKED, RUNNING, FINISHED, KILLED, FAILED, LOST)
    process_update = runner_ckpt.process_state
    if process_update is None:
      log.error(TaskCkptDispatcher.ErrorRecoveringState("Empty TaskRunnerCkpt encountered!"))
      return False

    def transition_to_waiting(process, process_history, seq=0):
      process_state = ProcessState(seq = seq, process = process, run_state = None)
      process_update = copy.deepcopy(process_state)
      process_update.run_state = ProcessRunState.WAITING
      process_history.runs.append(process_state)
      return process_state, process_update

    process = process_update.process
    if process not in state.processes:
      # Never seen this process before, create a ProcessHistory for it and initialize run 0.
      log.debug('Never encountered process (%s), initializing ProcessState' % process)
      process_history = ProcessHistory(process = process, runs = [], state = TaskState.ACTIVE)
      process_state, process_update = transition_to_waiting(process, process_history)
      state.processes[process] = process_history
    else:
      # We have seen this process, so the state update must pertain to the current run.
      process_history = state.processes[process]
      process_state = process_history.runs[-1]

      # Cannot have two consecutive terminal states
      #
      # You can go nonterminal=>nonterminal (normal), nonterminal=>terminal (success), and
      # terminal=>nonterminal (lost/failure) but not terminal=>terminal, so sanity check that.
      if TaskCkptDispatcher.is_terminal(process_state):
        if TaskCkptDispatcher.is_terminal(process_update):
          raise TaskCkptDispatcher.ErrorRecoveringState(
            "Received two consecutive terminal process states for %s!" % process)
        else:
          # We transitioned from terminal => nonterminal, so finish up the current run and
          # forge a new run.
          log.debug('Transitioning %s from terminal to nonterminal' % process)
          process_state, process_update = transition_to_waiting(process, process_history,
              seq=process_history.runs[-1].seq)
          process_update.seq = process_update.seq + 1

    # Run the process state machine.
    log.debug('Running state machine for process=%s/seq=%s' % (process_update.process,
        process_update.seq))
    return self.update_task_state(process_state, process_update, recovery)
