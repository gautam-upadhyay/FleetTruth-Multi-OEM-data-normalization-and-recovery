"""Submit with spark-submit; partition-pruned Parquet aggregates at cluster scale."""
import argparse
from pyspark.sql import SparkSession, functions as F

parser = argparse.ArgumentParser()
parser.add_argument("--input", required=True)
parser.add_argument("--output", required=True)
parser.add_argument("--from-day", required=True)
parser.add_argument("--to-day", required=True)
args = parser.parse_args()
spark = SparkSession.builder.appName("FleetTruth historical quality").getOrCreate()
events = spark.read.parquet(args.input).where((F.col("day") >= args.from_day) & (F.col("day") <= args.to_day))
report = events.groupBy("day", "oem").agg(F.count("id").alias("events"), F.sum(F.when(F.col("status") == "ACCEPTED", 1).otherwise(0)).alias("accepted"), F.avg("soc_pct").alias("mean_soc"))
report.write.mode("errorifexists").partitionBy("day").parquet(args.output)
spark.stop()
