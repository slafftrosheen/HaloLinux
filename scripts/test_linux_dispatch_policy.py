"""Offline JVM contract gate. This does NOT replace the full Gradle host suite."""
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
HOST = ROOT / 'host/linux/src/main/java/com/halo/host/linux'

class DispatchPolicyTest(unittest.TestCase):
    def test_real_policy_and_endpoint_against_canonical_contract(self):
        for name in ('LinuxInferenceEndpoint.kt', 'LinuxRemoteDispatchPolicy.kt', 'LinuxInferenceSettings.kt'):
            self.assertTrue((HOST / name).is_file(), f'{name} is not implemented')
        compiler = shutil.which('kotlinc')
        self.assertIsNotNone(compiler, 'Install a Kotlin compiler to run this explicit JVM gate')
        contract = (ROOT / 'sdk/provider-sdk/src/main/java/com/halo/sdk/provider/RemoteDispatchPolicy.kt').read_text()
        marker = '\nfun com.halo.sdk.runtime.Storage.remoteAiContext('
        self.assertEqual(contract.count(marker), 1, 'Canonical contract changed; review extraction')
        # Use unchanged interface/data declarations. Storage/provider extensions and
        # HTTP code are intentionally outside this isolated compilation gate.
        declarations = contract.split(marker)[0].replace('import kotlinx.coroutines.asContextElement\n', '')
        with tempfile.TemporaryDirectory(prefix='halo-dispatch-test-') as directory:
            work = pathlib.Path(directory)
            source = work / 'RemoteDispatchDeclarations.kt'
            source.write_text(declarations)
            jar = work / 'tests.jar'
            compile_result = subprocess.run([compiler, str(source),
                str(HOST / 'LinuxInferenceEndpoint.kt'), str(HOST / 'LinuxRemoteDispatchPolicy.kt'),
                str(HOST / 'LinuxInferenceSettings.kt'),
                str(ROOT / 'scripts/tests/LinuxDispatchPolicyHarness.kt'),
                '-jvm-target', '17', '-include-runtime', '-d', str(jar)],
                capture_output=True, text=True, timeout=90)
            self.assertEqual(compile_result.returncode, 0, compile_result.stderr)
            result = subprocess.run(['java', '-jar', str(jar)], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('ALL DISPATCH CASES PASSED', result.stdout)
            print(result.stdout, end='')

    def test_configuration_and_factory_composition_source_guard(self):
        # This is a SOURCE regression guard, not an executed YAML/HTTP integration test.
        config = (HOST / 'NodeConfig.kt').read_text()
        factory = (HOST / 'LinuxProviderLifecycle.kt').read_text()
        self.assertIn('LinuxInferenceSettings.from(inference)', config)
        self.assertIn('isAllowDuplicateKeys = false', config)
        self.assertIn('allowRemoteInference = settings.allowRemote', config)
        self.assertIn('withProviderDispatchPolicy(dispatchPolicy, providerId)', factory)
        self.assertIn('.proxy(Proxy.NO_PROXY)', factory)
        self.assertIn('destination.resolveHost(hostname)', factory)
        self.assertLess(factory.index('dispatchPolicy.close()'), factory.index('client.dispatcher.cancelAll()'))

if __name__ == '__main__':
    unittest.main(verbosity=2)
