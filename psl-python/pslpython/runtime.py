"""
This file is part of the PSL software.
Copyright 2011-2015 University of Maryland
Copyright 2013-2022 The Regents of the University of California

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
"""

import json
import os
import sys

import jpype
import jpype.imports

THIS_DIR = os.path.abspath(os.path.dirname(os.path.realpath(__file__)))
JAR_PATH = os.path.join(THIS_DIR, 'psl-runtime.jar')

def run(config, base_path = '.', jvm_options = []):
    runtime = _init(jvm_options)
    raw_output = runtime.serializedRun(json.dumps(config), base_path)
    return json.loads(str(raw_output))

def _shutdown():
    jpype.shutdownJVM()

def _init(jvm_options = []):
    if (not jpype.isJVMStarted()):
        jpype.startJVM(jpype.getDefaultJVMPath(), *jvm_options, classpath = [JAR_PATH])

    from org.linqs.psl.runtime import Runtime
    return Runtime

def main(path):
    with open(path, 'r') as file:
        config = json.load(file)

    base_path = os.path.dirname(path)
    output = run(config, base_path)

    print(json.dumps(output, indent = 4))

def _load_args(args):
    executable = args.pop(0)
    if (len(args) != 1 or ({'h', 'help'} & {arg.lower().strip().replace('-', '') for arg in args})):
        print("USAGE: python3 %s <config path>" % (executable), file = sys.stderr)
        sys.exit(1)

    return args.pop(0)

if (__name__ == '__main__'):
    main(_load_args(sys.argv))