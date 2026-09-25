import React from 'react';
import {
  Button,
  LayoutChangeEvent,
  Platform,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import Animated, {
  useAnimatedStyle,
  useDerivedValue,
} from 'react-native-reanimated';
import {
  Clamshell,
  useClamshellCapabilities,
  useFoldState,
  useHingeAngle,
} from 'react-native-clamshell';
import type { FoldGeometry } from 'react-native-clamshell';

function formatAngle(angle: number | null | undefined): string {
  return angle == null ? 'No angle sample' : `${angle.toFixed(0)}°`;
}

function formatRange(
  range: ReturnType<typeof useClamshellCapabilities>['angleRange'],
): string {
  return range == null ? 'unknown' : `${range.min}°-${range.max}°`;
}

function formatGeometry(geometry: FoldGeometry | null): string {
  if (geometry == null) return 'none';
  const { bounds } = geometry;
  return `${bounds.x.toFixed(0)},${bounds.y.toFixed(
    0,
  )} ${bounds.width.toFixed(0)}x${bounds.height.toFixed(0)} ${
    geometry.isSeparating ? 'separating' : 'non-separating'
  } ${geometry.occlusionType}`;
}

function blockJs(milliseconds = 1500) {
  const end = Date.now() + milliseconds;
  while (Date.now() < end) {
    Math.sqrt(Date.now());
  }
}

function AngleConsumer({ label }: { label: string }): React.JSX.Element {
  const [angle, setAngle] = React.useState<number | null>(
    () => Clamshell.getSnapshot().angle,
  );

  React.useEffect(() => {
    const unsubscribe = Clamshell.onAngle(setAngle);
    setAngle(Clamshell.getSnapshot().angle);
    return unsubscribe;
  }, []);

  return (
    <Text
      accessibilityLabel={`${label} ${formatAngle(angle)}`}
      style={styles.row}
    >
      {label}: {formatAngle(angle)}
    </Text>
  );
}

function AngleAnimation(): React.JSX.Element {
  const angle = useHingeAngle();
  const rotation = useDerivedValue(() => angle.value ?? 0);
  const animatedStyle = useAnimatedStyle(() => ({
    transform: [{ rotate: `${rotation.value}deg` }],
    opacity: angle.value == null ? 0.35 : 1,
  }));

  return (
    <View style={styles.animationStage}>
      <Animated.View
        accessibilityLabel="UI runtime hinge angle animation"
        style={[styles.animatedNeedle, animatedStyle]}
      />
      <Text style={styles.caption}>Animated directly from useHingeAngle()</Text>
    </View>
  );
}

function GeometryOverlay({
  geometry,
  rootSize,
}: {
  geometry: FoldGeometry | null;
  rootSize: { width: number; height: number };
}): React.JSX.Element | null {
  if (geometry == null || rootSize.width === 0 || rootSize.height === 0) {
    return null;
  }
  const { bounds } = geometry;
  return (
    <View
      pointerEvents="none"
      accessibilityLabel="Root-relative fold geometry overlay"
      style={[
        styles.geometryOverlay,
        {
          left: bounds.x,
          top: bounds.y,
          width: Math.max(bounds.width, 2),
          height: Math.max(bounds.height, 2),
        },
      ]}
    />
  );
}

function App(): React.JSX.Element {
  const state = useFoldState();
  const capabilities = useClamshellCapabilities();
  const [showConsumers, setShowConsumers] = React.useState(true);
  const [rootSize, setRootSize] = React.useState({ width: 0, height: 0 });
  const useRightPane =
    Platform.OS === 'ios' &&
    capabilities.isFoldable &&
    state.posture !== 'closed';

  const onRootLayout = React.useCallback((event: LayoutChangeEvent) => {
    const { width, height } = event.nativeEvent.layout;
    setRootSize({ width, height });
  }, []);

  return (
    <View style={styles.root} onLayout={onRootLayout}>
      <GeometryOverlay geometry={state.geometry} rootSize={rootSize} />
      <ScrollView
        contentContainerStyle={[
          styles.container,
          useRightPane && styles.rightPane,
        ]}
        accessibilityLabel="Clamshell diagnostics screen"
      >
        <Text style={styles.title}>Clamshell diagnostics</Text>

        <View style={styles.card}>
          <Text style={styles.section}>Capabilities</Text>
          <Text style={styles.row}>
            Detection: {capabilities.detectionStatus}
          </Text>
          <Text style={styles.row}>
            Foldability:{' '}
            {capabilities.detectionStatus === 'pending'
              ? 'pending'
              : capabilities.isFoldable
                ? 'foldable'
                : 'not foldable'}
          </Text>
          <Text style={styles.row}>
            Continuous angle: {capabilities.hasContinuousAngle ? 'yes' : 'no'}
          </Text>
          <Text style={styles.row}>
            Angle range: {formatRange(capabilities.angleRange)}
          </Text>
          <Text style={styles.row}>
            Postures:{' '}
            {capabilities.supportedPostures.length === 0
              ? 'none'
              : capabilities.supportedPostures.join(', ')}
          </Text>
          <Text style={styles.row}>
            Fold geometry: {capabilities.hasFoldGeometry ? 'yes' : 'no'}
          </Text>
        </View>

        <View style={styles.card}>
          <Text style={styles.section}>State</Text>
          <Text style={styles.row}>Posture: {state.posture}</Text>
          <Text style={styles.row}>Orientation: {state.orientation}</Text>
          <Text style={styles.row}>
            Snapshot angle: {formatAngle(state.angle)}
          </Text>
          <Text style={styles.row}>
            Geometry: {formatGeometry(state.geometry)}
          </Text>
        </View>

        <View style={styles.card}>
          <Text style={styles.section}>Angle delivery</Text>
          {showConsumers ? (
            <>
              <AngleConsumer label="Angle consumer A" />
              <AngleConsumer label="Angle consumer B" />
            </>
          ) : (
            <Text style={styles.row}>Angle consumers unmounted</Text>
          )}
          <AngleAnimation />
          <View style={styles.buttonRow}>
            <Button
              title={showConsumers ? 'Unmount consumers' : 'Mount consumers'}
              onPress={() => setShowConsumers(value => !value)}
            />
          </View>
          <View style={styles.buttonRow}>
            <Button title="Block JS for 1.5s" onPress={() => blockJs()} />
          </View>
        </View>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#07130d',
  },
  container: {
    gap: 14,
    padding: 20,
    paddingBottom: 48,
  },
  rightPane: {
    marginLeft: '50%',
  },
  title: {
    color: '#83f28f',
    fontSize: 32,
    fontWeight: '800',
  },
  card: {
    backgroundColor: '#102217',
    borderColor: '#2f7044',
    borderRadius: 16,
    borderWidth: 1,
    gap: 8,
    padding: 16,
  },
  section: {
    color: '#c8ffd0',
    fontSize: 22,
    fontWeight: '700',
  },
  row: {
    color: '#dfffe4',
    fontSize: 18,
  },
  caption: {
    color: '#a9d9b2',
    fontSize: 14,
  },
  animationStage: {
    alignItems: 'center',
    gap: 8,
    marginTop: 8,
  },
  animatedNeedle: {
    backgroundColor: '#83f28f',
    borderRadius: 4,
    height: 12,
    width: 120,
  },
  buttonRow: {
    marginTop: 8,
  },
  geometryOverlay: {
    backgroundColor: 'rgba(255, 224, 102, 0.45)',
    borderColor: '#ffe066',
    borderWidth: 2,
    position: 'absolute',
    zIndex: 10,
  },
});

export default App;
