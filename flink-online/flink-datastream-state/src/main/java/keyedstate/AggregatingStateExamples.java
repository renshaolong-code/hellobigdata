package keyedstate;

import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.state.AggregatingState;
import org.apache.flink.api.common.state.AggregatingStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.state.memory.MemoryStateBackend;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.util.Collector;

import java.util.concurrent.TimeUnit;

/**
 * AggregatingState<T>保存一个聚合值
 * add(T)添加元素时，按照AggregatingStateDescriptor描述里AggregateFunction方法进行聚合
 * 相比于ReducingState更加灵活
 */
public class AggregatingStateExamples {
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        // 并行度设置
        env.setParallelism(4);
        // 获取默认并行度 本地->CPU核数 集群->默认配置
        //int pm = env.getParallelism();
        // 设置Checkpoint 每个60*1000ms一次cp
        env.enableCheckpointing(60 * 1000, CheckpointingMode.EXACTLY_ONCE);
        // 10分钟内 重启三次 每次间隔10秒 超过则job失败
        env.setRestartStrategy(RestartStrategies.failureRateRestart(3, org.apache.flink.api.common.time.Time.of(10, TimeUnit.MINUTES), org.apache.flink.api.common.time.Time.of(10,TimeUnit.SECONDS)));
        // 设置statebackend 暂用Memory
        env.setStateBackend(new MemoryStateBackend(true));
        // 设置EventTime为事件时间
        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);

        // source 模拟数据源 自定义
        DataStream<Tuple3<String,Integer,Long>> dataStream = env.addSource(new SourceFunction<Tuple3<String, Integer, Long>>() {
            public void run(SourceContext<Tuple3<String, Integer, Long>> sourceContext) throws Exception {
                while (true) {
                    if(System.currentTimeMillis() %2 ==0) {
                        sourceContext.collect(new Tuple3<String,Integer,Long>("SZ", 1, System.currentTimeMillis()));
                    }else{
                        sourceContext.collect(new Tuple3<String, Integer, Long>("BJ", 10, System.currentTimeMillis()));
                    }

                    Thread.sleep(1000);
                }
            }

            public void cancel() {}
        });

        // 计算
        dataStream.keyBy(new KeySelector<Tuple3<String, Integer, Long>, String>() {
            public String getKey(Tuple3<String, Integer, Long> tupleInput) throws Exception {
                return tupleInput.f0;
            }
        })
                .flatMap(new RichFlatMapFunction<Tuple3<String, Integer, Long>, Tuple2<String, Integer>>() {
                    private AggregatingState<Tuple2<String, Integer>, Tuple2<String, Integer>> aggregatingState;

                    @Override
                    public void open(Configuration parameters) throws Exception {
                        super.open(parameters);
                        AggregatingStateDescriptor<Tuple2<String, Integer>,Tuple2<String, Integer>,Tuple2<String, Integer>> aggregatingStateDescriptor=
                                new AggregatingStateDescriptor<Tuple2<String, Integer>, Tuple2<String, Integer>, Tuple2<String, Integer>>(
                                        ""
                                        , new AggregateFunction<Tuple2<String, Integer>, Tuple2<String, Integer>, Tuple2<String, Integer>>() {
                                            public Tuple2<String, Integer> createAccumulator() {
                                                return Tuple2.of("", 0);
                                            }

                                            public Tuple2<String, Integer> add(Tuple2<String, Integer> tupleInput, Tuple2<String, Integer> tupleAcc) {
                                                if (tupleAcc.f0.equals(tupleInput.f0)) {
                                                    return Tuple2.of(tupleInput.f0, tupleInput.f1 + tupleAcc.f1);
                                                } else {
                                                    return tupleInput;
                                                }
                                            }

                                            public Tuple2<String, Integer> getResult(Tuple2<String, Integer> tupleAcc) {
                                                return tupleAcc;
                                            }

                                            public Tuple2<String, Integer> merge(Tuple2<String, Integer> stringIntegerTuple2, Tuple2<String, Integer> acc1) {
                                                return null;
                                            }
                                        }
                                        , TypeInformation.of(new TypeHint<Tuple2<String, Integer>>() {})
                                );

                        aggregatingState = getRuntimeContext().getAggregatingState(aggregatingStateDescriptor);
                    }

                    @Override
                    public void flatMap(Tuple3<String, Integer, Long> tupleInput, Collector<Tuple2<String, Integer>> collector) throws Exception {
                        // 执行AggregateFunction add
                        aggregatingState.add(Tuple2.of(tupleInput.f0, tupleInput.f1));
                        // 执行AggregateFunction getResult
                        Tuple2<String, Integer> tupleOutput = aggregatingState.get();
                        collector.collect(tupleOutput);
                        //aggregatingState.clear();
                    }
                })
                .print();

        env.execute("zmboosum");
    }
}
