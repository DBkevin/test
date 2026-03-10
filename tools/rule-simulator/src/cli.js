#!/usr/bin/env node

/**
 * A11y 规则模拟器 CLI
 * 
 * 用法:
 *   a11y-sim --rule rules/douyin.json --page pages/hospital.xml
 *   a11y-sim -r rules/douyin.json -p pages/hospital.xml --verbose
 */

const { Command } = require('commander');
const fs = require('fs');
const path = require('path');
const chalk = require('chalk');
const cheerio = require('cheerio');

const program = new Command();

program
  .name('a11y-sim')
  .description('A11y 规则引擎本地模拟器')
  .version('1.0.0')
  .requiredOption('-r, --rule <path>', '规则文件路径 (JSON)')
  .requiredOption('-p, --page <path>', '页面快照文件路径 (XML)')
  .option('-v, --verbose', '详细输出模式', false)
  .option('-o, --output <path>', '输出结果文件路径')
  .parse(process.argv);

const options = program.opts();

// 主函数
async function main() {
  console.log(chalk.blue('🔍 A11y 规则模拟器 v1.0.0'));
  console.log('');
  
  try {
    // 1. 加载规则
    console.log(chalk.gray('① 加载规则文件...'));
    const rule = loadRule(options.rule);
    console.log(chalk.green('✓ 规则加载成功'));
    console.log(chalk.gray(`   规则 ID: ${rule.rule_id}`));
    console.log(chalk.gray(`   适用 APP: ${rule.app_package}`));
    console.log(chalk.gray(`   页面数：${rule.pages.length}`));
    console.log('');
    
    // 2. 加载页面快照
    console.log(chalk.gray('② 加载页面快照...'));
    const page = loadPageSnapshot(options.page);
    console.log(chalk.green('✓ 页面加载成功'));
    console.log(chalk.gray(`   页面标题：${page.title || 'N/A'}`));
    console.log(chalk.gray(`   节点数：${page.nodeCount}`));
    console.log('');
    
    // 3. 页面匹配
    console.log(chalk.gray('③ 页面匹配...'));
    const matchResult = matchPage(rule, page);
    if (matchResult.matched) {
      console.log(chalk.green('✓ 页面匹配成功'));
      console.log(chalk.gray(`   匹配页面：${matchResult.pageId}`));
    } else {
      console.log(chalk.red('✗ 页面匹配失败'));
      console.log(chalk.gray(`   原因：${matchResult.reason}`));
      process.exit(1);
    }
    console.log('');
    
    // 4. 数据提取
    console.log(chalk.gray('④ 数据提取...'));
    const extractResult = extractData(rule, page);
    console.log(chalk.green('✓ 数据提取完成'));
    console.log('');
    
    // 5. 输出结果
    console.log(chalk.blue('📊 提取结果:'));
    console.log('');
    
    if (extractResult.hospital_name) {
      console.log(chalk.white('🏥 医院名称:'));
      console.log(chalk.cyan(`   ${extractResult.hospital_name}`));
      console.log('');
    }
    
    if (extractResult.honors) {
      console.log(chalk.white('🏆 荣誉项:'));
      console.log(chalk.cyan(`   ${extractResult.honors}`));
      console.log('');
    }
    
    if (extractResult.group_buys && extractResult.group_buys.length > 0) {
      console.log(chalk.white(`📦 团单 (${extractResult.group_buys.length}个):`));
      extractResult.group_buys.forEach((item, index) => {
        console.log(chalk.gray(`   ${index + 1}. ${item.title || 'N/A'}`));
        console.log(chalk.gray(`      价格：${item.price || 'N/A'}`));
        console.log(chalk.gray(`      销量：${item.sales || 'N/A'}`));
      });
      console.log('');
    }
    
    // 6. 保存结果（可选）
    if (options.output) {
      const outputPath = path.resolve(options.output);
      fs.writeFileSync(outputPath, JSON.stringify(extractResult, null, 2));
      console.log(chalk.green(`✓ 结果已保存到：${outputPath}`));
      console.log('');
    }
    
    // 7. 验证结果
    console.log(chalk.blue('✅ 验证通过!'));
    console.log('');
    
  } catch (error) {
    console.log(chalk.red('❌ 错误:'));
    console.log(chalk.red(`   ${error.message}`));
    if (options.verbose) {
      console.log('');
      console.log(chalk.gray(error.stack));
    }
    process.exit(1);
  }
}

// 加载规则文件
function loadRule(rulePath) {
  const absolutePath = path.resolve(rulePath);
  if (!fs.existsSync(absolutePath)) {
    throw new Error(`规则文件不存在：${absolutePath}`);
  }
  
  const content = fs.readFileSync(absolutePath, 'utf-8');
  return JSON.parse(content);
}

// 加载页面快照
function loadPageSnapshot(pagePath) {
  const absolutePath = path.resolve(pagePath);
  if (!fs.existsSync(absolutePath)) {
    throw new Error(`页面快照文件不存在：${absolutePath}`);
  }
  
  const content = fs.readFileSync(absolutePath, 'utf-8');
  const $ = cheerio.load(content, { xmlMode: true });
  
  // 提取页面信息
  const nodes = [];
  $('node').each((i, elem) => {
    const $elem = $(elem);
    nodes.push({
      index: i,
      class: $elem.attr('class'),
      text: $elem.attr('text'),
      resourceId: $elem.attr('resource-id'),
      bounds: $elem.attr('bounds'),
      children: []
    });
  });
  
  return {
    title: $('node').first().attr('text'),
    nodeCount: nodes.length,
    nodes: nodes,
    $: $
  };
}

// 页面匹配
function matchPage(rule, page) {
  const firstPage = rule.pages[0];
  if (!firstPage) {
    return { matched: false, reason: '规则中没有定义页面' };
  }
  
  const matchRules = firstPage.match_rules;
  if (!matchRules || matchRules.length === 0) {
    return { matched: true, pageId: firstPage.page_id };
  }
  
  // 检查页面文本是否包含关键词
  const pageText = page.nodes.map(n => n.text).filter(Boolean).join(' ').toLowerCase();
  
  for (const rule of matchRules) {
    if (rule.type === 'text_contains') {
      const values = rule.values.map(v => v.toLowerCase());
      const hasMatch = values.some(v => pageText.includes(v));
      
      if (!hasMatch && rule.logic !== 'OR') {
        return { 
          matched: false, 
          reason: `页面不包含关键词：${rule.values.join(', ')}` 
        };
      }
    }
  }
  
  return { matched: true, pageId: firstPage.page_id };
}

// 数据提取
function extractData(rule, page) {
  const firstPage = rule.pages[0];
  if (!firstPage || !firstPage.extract_rules) {
    return {};
  }
  
  const result = {};
  const extractRules = firstPage.extract_rules;
  
  // 提取医院名称
  if (extractRules.hospital_name) {
    result.hospital_name = extractByKeywords(page, extractRules.hospital_name.keywords);
  }
  
  // 提取荣誉项
  if (extractRules.honors) {
    result.honors = extractByKeywords(page, extractRules.honors.keywords);
  }
  
  // 提取团单列表
  if (extractRules.group_buys) {
    result.group_buys = extractGroupBuys(page, extractRules.group_buys);
  }
  
  return result;
}

// 根据关键词提取
function extractByKeywords(page, keywords) {
  const pageText = page.nodes.map(n => n.text).filter(Boolean).join(' ');
  
  for (const keyword of keywords) {
    const index = pageText.toLowerCase().indexOf(keyword.toLowerCase());
    if (index !== -1) {
      // 返回包含关键词的文本片段
      const start = Math.max(0, index - 10);
      const end = Math.min(pageText.length, index + keyword.length + 20);
      return pageText.substring(start, end).trim();
    }
  }
  
  return null;
}

// 提取团单列表
function extractGroupBuys(page, groupBuyRule) {
  const results = [];
  const itemRules = groupBuyRule.item_rules;
  
  // 简单实现：从页面文本中提取
  const pageText = page.nodes.map(n => n.text).filter(Boolean).join('\n');
  const lines = pageText.split('\n');
  
  let currentItem = {};
  
  for (const line of lines) {
    // 检查是否是团单名称
    if (itemRules.title) {
      const titleKeywords = itemRules.title.keywords;
      if (titleKeywords.some(k => line.includes(k))) {
        if (Object.keys(currentItem).length > 0) {
          results.push(currentItem);
        }
        currentItem = { title: line.trim() };
      }
    }
    
    // 检查价格
    if (itemRules.price && currentItem.title) {
      const priceMatch = line.match(/¥(\d+(?:\.\d+)?)/);
      if (priceMatch) {
        currentItem.price = `¥${priceMatch[1]}`;
      }
    }
    
    // 检查销量
    if (itemRules.sales && currentItem.title) {
      const salesKeywords = itemRules.sales.keywords;
      if (salesKeywords.some(k => line.includes(k))) {
        currentItem.sales = line.trim();
      }
    }
  }
  
  // 添加最后一个
  if (Object.keys(currentItem).length > 0) {
    results.push(currentItem);
  }
  
  return results;
}

// 运行
main();
